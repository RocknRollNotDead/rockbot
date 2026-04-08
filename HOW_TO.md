# Инструкции по доработке бота

## Содержание

1. [Автоматическая отправка фидбека админам](#1-автоматическая-отправка-фидбека-админам)
2. [Один пользователь — один запрос + убрать кнопки после ответа](#2-один-пользователь--один-запрос--убрать-кнопки-после-ответа)

---

## 1. Автоматическая отправка фидбека админам

### Что нужно сделать

Сейчас фидбек сохраняется в базу и лежит там до тех пор, пока администратор сам не запросит его командой `/inbox`. Нужно:

- Когда пользователь отправляет фидбек → автоматически слать уведомление получателю запросов
- Добавить настройку включения/отключения этой функции (по умолчанию включена)
- Добавить команду `/togglefeedbacknotify` для включения/отключения

---

### Шаг 1. Добавить настройку в `DatabaseManager.java`

Таблица `settings` уже существует и поддерживает ключ-значение.

Добавьте вспомогательный метод рядом с `getSettingLong()` и `setSetting()`:

```java
// DatabaseManager.java
/** Проверяет, включены ли автоматические уведомления о фидбеке */
public static boolean isFeedbackNotifyEnabled() {
    // Читаем настройку; "1" = включено, "0" = выключено.
    // По умолчанию включено (defaultValue = 1).
    return getSettingLong("feedback_notify", 1L) == 1L;
}
```

---

### Шаг 2. Изменить обработку фидбека в `MessageHandler.java`

Найдите блок `case AWAIT_FEEDBACK` в методе `handleState()` (сейчас выглядит так):

```java
case AWAIT_FEEDBACK -> {
    DatabaseManager.saveFeedback(userId, "user_" + userId, text);
    UserSession.clearState(userId);
    bot.editNav(chatId, navMsg, "💌 *Спасибо!* ...", Keyboards.backToHome());
}
```

Замените на:

```java
case AWAIT_FEEDBACK -> {
    // Сохраняем фидбек в базу
    DatabaseManager.saveFeedback(userId, "user_" + userId, text);
    UserSession.clearState(userId);
    bot.editNav(chatId, navMsg, "💌 Спасибо! Ваше сообщение отправлено группе. 🤘",
            Keyboards.backToHome());

    // Автоматически уведомляем получателя, если функция включена
    if (DatabaseManager.isFeedbackNotifyEnabled()) {
        long recipient = BotConfig.getRequestRecipient();
        if (recipient > 0) {
            // Текст БЕЗ Markdown — пользовательский текст может содержать * _ ` 
            String notify = "💌 Новый фидбек от пользователя user_" + userId + ":\n\n" + text;
            bot.sendText(recipient, notify);
        }
    }
}
```

---

### Шаг 3. Добавить команду включения/отключения в `MessageHandler.java`

В методе `handleCommand()` добавьте новый case в блок администраторских команд:

```java
// В handleCommand():
case "/togglefeedbacknotify" -> guardAdmin(userId, chatId, () -> cmdToggleFeedbackNotify(chatId));
```

Добавьте метод рядом с остальными `cmdXxx`:

```java
/**
 * /togglefeedbacknotify — включает или выключает автоматическую отправку
 * фидбека получателю уведомлений.
 */
private void cmdToggleFeedbackNotify(long chatId) {
    boolean current = DatabaseManager.isFeedbackNotifyEnabled();
    // Инвертируем текущее значение
    DatabaseManager.setSetting("feedback_notify", current ? "0" : "1");
    boolean next = !current;
    bot.sendText(chatId,
            "💌 Автоматические уведомления о фидбеке: " +
            (next ? "✅ ВКЛЮЧЕНЫ" : "❌ ВЫКЛЮЧЕНЫ"));
}
```

---

### Шаг 4. Добавить команду в `/help`

В методе `cmdHelp()` в секцию для администраторов добавьте строку:

```java
// В строке isAdmin-секции:
"/togglefeedbacknotify — вкл/выкл автоуведомления о фидбеке\n"
```

---

### Как это работает

```
Пользователь → пишет фидбек
     ↓
saveFeedback() — сохраняем в таблицу feedback (для /inbox)
     ↓
isFeedbackNotifyEnabled() == true?
     ├─ ДА  → sendText(recipient, текст_фидбека)
     └─ НЕТ → ничего не делаем, фидбек всё равно лежит в /inbox
```

Фидбек **всегда** сохраняется в базе и доступен через `/inbox`. Настройка влияет только на мгновенное уведомление.

---

## 2. Один пользователь — один запрос + убрать кнопки после ответа

### Часть А: один пользователь — один запрос

#### Что нужно изменить

Сейчас `insertMemberRequest()` всегда делает `INSERT` — у одного пользователя может быть несколько строк. Нужно сделать **INSERT OR REPLACE** (уточнение: в SQLite это работает с `UNIQUE` ограничением), но при этом:

- Если у пользователя уже есть запрос со статусом `pending` — **обновляем текст** и **не посылаем новое уведомление** (оно уже ушло)
- Если предыдущий запрос был `approved` или `denied` — **создаём новый** и посылаем уведомление
- Счётчик спама идёт отдельно и **не сбрасывается** при обновлении запроса

#### Шаг 1. Изменить схему таблицы в `DatabaseManager.java`

В `initializeDatabase()` измените схему таблицы `member_requests`:

```java
// Добавляем UNIQUE на user_id — один активный запрос на пользователя
stmt.execute("""
    CREATE TABLE IF NOT EXISTS member_requests (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id    INTEGER NOT NULL UNIQUE,  -- ДОБАВЛЕНО UNIQUE
        username   TEXT,
        message    TEXT,
        status     TEXT NOT NULL DEFAULT 'pending',
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )
""");
```

> **Важно:** если таблица уже существует, `CREATE TABLE IF NOT EXISTS` не изменит её схему.
> После добавления `UNIQUE` нужно либо удалить `rockbot.db` (потеряете данные),
> либо вручную выполнить миграцию (см. ниже).

**Миграция без потери данных** — добавьте после `CREATE TABLE`:

```java
// Миграция: создаём новую таблицу с UNIQUE и переносим данные
// Безопасно запускать при каждом старте — если таблица уже правильная, ничего не изменится
try {
    stmt.execute("""
        CREATE TABLE IF NOT EXISTS member_requests_new (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id    INTEGER NOT NULL UNIQUE,
            username   TEXT,
            message    TEXT,
            status     TEXT NOT NULL DEFAULT 'pending',
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        )
    """);
    // Переносим только последний запрос каждого пользователя
    stmt.execute("""
        INSERT OR IGNORE INTO member_requests_new (id, user_id, username, message, status, created_at)
        SELECT id, user_id, username, message, status, created_at
        FROM member_requests
        WHERE id IN (SELECT MAX(id) FROM member_requests GROUP BY user_id)
    """);
    stmt.execute("DROP TABLE IF EXISTS member_requests");
    stmt.execute("ALTER TABLE member_requests_new RENAME TO member_requests");
} catch (SQLException migrationEx) {
    log.debug("Миграция member_requests: {}", migrationEx.getMessage());
}
```

#### Шаг 2. Заменить `insertMemberRequest()` в `DatabaseManager.java`

Удалите старый метод и добавьте новый с логикой обновления:

```java
/**
 * Создаёт или обновляет запрос на участие.
 *
 * Логика:
 *  - Если у пользователя есть PENDING-запрос → обновляем текст и возвращаем id.
 *    Возвращаем отрицательный id (-reqId) чтобы вызывающий код знал:
 *    уведомление уже было отправлено раньше, повторно слать не нужно.
 *  - Если запроса нет или предыдущий был approved/denied → создаём новый.
 *    Возвращаем положительный id → вызывающий код отправляет уведомление.
 *
 * @return  +id → новый запрос (нужно уведомить получателя)
 *          -id → обновлён существующий pending (НЕ уведомлять повторно)
 *          -1  → ошибка
 */
public static long upsertMemberRequest(long userId, String username, String message) {
    try (Connection conn = DriverManager.getConnection(DB_URL)) {

        // Проверяем, есть ли уже pending-запрос от этого пользователя
        long existingId = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM member_requests WHERE user_id = ? AND status = 'pending'")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existingId = rs.getLong("id");
            }
        }

        if (existingId > 0) {
            // Pending-запрос уже есть — обновляем текст и время
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE member_requests SET message = ?, username = ?, created_at = datetime('now') " +
                    "WHERE id = ?")) {
                ps.setString(1, message);
                ps.setString(2, username != null ? username : "id:" + userId);
                ps.setLong(3, existingId);
                ps.executeUpdate();
            }
            // Отрицательный id = "обновлено, не уведомляй повторно"
            return -existingId;
        }

        // Нового запроса нет (или был, но уже approved/denied) — создаём
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO member_requests (user_id, username, message, status) " +
                "VALUES (?, ?, ?, 'pending')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, username != null ? username : "id:" + userId);
            ps.setString(3, message);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }

    } catch (SQLException e) { log.error("upsertMemberRequest", e); return -1; }
}
```

#### Шаг 3. Обновить обработчик запроса в `MessageHandler.java`

Найдите блок `case AWAIT_MEMBER_REQUEST_MSG` и замените вызов `insertMemberRequest` на `upsertMemberRequest`:

```java
case AWAIT_MEMBER_REQUEST_MSG -> {
    // Проверяем антиспам
    String spamBlock = DatabaseManager.checkRequestSpam(userId, "user_" + userId);
    if (spamBlock != null) {
        UserSession.clearState(userId);
        bot.editNav(chatId, navMsg, spamBlock, Keyboards.backToHome());
        return;
    }

    String message = text.equalsIgnoreCase("пропустить") ? "" : text;

    // Создаём или обновляем запрос
    // Возвращает: +id (новый) или -id (обновлён существующий)
    long result = DatabaseManager.upsertMemberRequest(userId, "user_" + userId, message);

    UserSession.clearState(userId);

    if (result == -1) {
        // Ошибка базы данных
        bot.editNav(chatId, navMsg, "❌ Не удалось отправить запрос. Попробуйте позже.",
                Keyboards.backToHome());
        return;
    }

    if (result > 0) {
        // Новый запрос — показываем подтверждение и уведомляем получателя
        bot.editNav(chatId, navMsg,
                "📨 Запрос отправлен!\n\nАдминистраторы рассмотрят его и ответят вам.",
                Keyboards.backToHome());

        long recipient = BotConfig.getRequestRecipient();
        if (recipient > 0) {
            String adminMsg = "📨 Новый запрос на участие\n\n" +
                    "Пользователь: user_" + userId + "\n" +
                    "ID: " + userId + "\n" +
                    (message.isBlank() ? "Без пояснения" : "Сообщение: " + message);
            bot.sendNotification(recipient, adminMsg, Keyboards.memberRequestActions(result));
        }

    } else {
        // result < -1: запрос обновлён (был pending, изменили текст)
        // Новое уведомление не отправляем — оно уже было отправлено раньше
        bot.editNav(chatId, navMsg,
                "📨 Ваш запрос обновлён.\n\nАдминистраторы уже получили уведомление и рассматривают его.",
                Keyboards.backToHome());
    }
}
```

---

### Часть Б: убрать кнопки после принятия или отклонения запроса

#### Проблема

Уведомление с кнопками «Принять / Отклонить» отправляется через `sendNotification()`. После нажатия кнопки `editNav()` меняет **nav-сообщение администратора** — но не то сообщение с кнопками, которое было отправлено уведомлением. Поэтому кнопки остаются и можно нажать повторно.

Кроме того, запрос уже переведён в статус `approved` или `denied`, и при повторном нажатии `getMemberRequest(reqId)` вернёт запись с этим статусом — нужно проверять его.

#### Решение: заменить `editNav` на `editMessage` конкретного сообщения

В `CallbackHandler.java` нажатие кнопок приходит как `CallbackQuery`. У объекта `CallbackQuery` есть `getMessage()` — это **то самое** сообщение с кнопками. Его `messageId` и `chatId` нам уже известны (`msgId` и `chatId` в методе `handle()`).

Значит `bot.editNav(chatId, msgId, ...)` **уже редактирует правильное сообщение** — сообщение с кнопками уведомления. Проблема в другом: после редактирования `Keyboards.backToHome()` добавляет кнопку «🔙 Главное меню», которая ссылается на nav-сообщение администратора, а не на это. Это приводит к путанице.

Нужно:
1. Проверять статус запроса перед обработкой — если уже `approved`/`denied`, просто убрать кнопки
2. После принятия/отклонения заменять кнопки на текст без кнопок

#### Шаг 1. Добавить метод проверки статуса в `DatabaseManager.java`

```java
/** Возвращает статус запроса: "pending", "approved", "denied" или null если не найден */
public static String getMemberRequestStatus(long reqId) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
             "SELECT status FROM member_requests WHERE id = ?")) {
        ps.setLong(1, reqId);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("status") : null;
        }
    } catch (SQLException e) { log.error("getMemberRequestStatus", e); return null; }
}
```

#### Шаг 2. Добавить метод `editMessageNoKeyboard()` в `RockBandBot.java`

Этот метод редактирует конкретное сообщение с кнопками уведомления, убирая кнопки:

```java
// RockBandBot.java
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;

/**
 * Убирает клавиатуру у произвольного сообщения (не обязательно nav).
 * Используется для уведомлений с кнопками после обработки запроса.
 */
public void removeKeyboard(long chatId, int messageId) {
    try {
        EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setReplyMarkup(new InlineKeyboardMarkup(List.of()));
        execute(edit);
    } catch (Exception e) {
        log.debug("removeKeyboard chatId={} msgId={}: {}", chatId, messageId,
                  e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
    }
}

/**
 * Редактирует текст произвольного сообщения (не nav).
 * Используется для уведомлений — меняем текст и убираем кнопки.
 */
public void editMessage(long chatId, int messageId, String text) {
    try {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setText(text);
        // Без parseMode — текст содержит пользовательские данные
        execute(edit);
    } catch (Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("message is not modified")) return;
        log.error("editMessage chatId={} msgId={}", chatId, messageId, e);
    }
}
```

#### Шаг 3. Изменить `handleApproveRequest()` и `handleDenyRequest()` в `CallbackHandler.java`

```java
private void handleApproveRequest(long chatId, int msgId, long adminId, long reqId) {
    if (!BotConfig.isAdmin(adminId)) {
        // Убираем кнопки — этот admin не имеет прав
        bot.removeKeyboard(chatId, msgId);
        return;
    }

    // Проверяем, не был ли запрос уже обработан другим администратором
    String status = DatabaseManager.getMemberRequestStatus(reqId);
    if (!"pending".equals(status)) {
        // Запрос уже обработан — просто убираем кнопки у этого сообщения
        // (другой admin мог нажать раньше)
        String info = "approved".equals(status) ? "✅ Запрос уже был принят ранее." : "❌ Запрос уже был отклонён ранее.";
        bot.editMessage(chatId, msgId, info);
        return;
    }

    String[] req = DatabaseManager.getMemberRequest(reqId);
    if (req == null) {
        bot.editMessage(chatId, msgId, "❌ Запрос не найден.");
        return;
    }

    long   targetId = Long.parseLong(req[1]);
    String username = req[2];

    DatabaseManager.addMember(targetId, username, adminId);
    DatabaseManager.approveMemberRequest(reqId);

    // Редактируем САМО сообщение с кнопками — убираем кнопки, меняем текст
    bot.editMessage(chatId, msgId,
            "✅ Принято\n\nПользователь " + username + " добавлен как участник группы.\n" +
            "(принято администратором " + adminId + ")");

    // Уведомляем пользователя
    bot.sendText(targetId,
            "🎉 Ваш запрос принят!\n\nВы теперь участник группы " + BotConfig.BAND_NAME + ".\nНажмите /start.");
}

private void handleDenyRequest(long chatId, int msgId, long adminId, long reqId) {
    if (!BotConfig.isAdmin(adminId)) {
        bot.removeKeyboard(chatId, msgId);
        return;
    }

    // Проверяем, не был ли запрос уже обработан
    String status = DatabaseManager.getMemberRequestStatus(reqId);
    if (!"pending".equals(status)) {
        String info = "approved".equals(status) ? "✅ Запрос уже был принят ранее." : "❌ Запрос уже был отклонён ранее.";
        bot.editMessage(chatId, msgId, info);
        return;
    }

    String[] req = DatabaseManager.getMemberRequest(reqId);
    if (req == null) {
        bot.editMessage(chatId, msgId, "❌ Запрос не найден.");
        return;
    }

    long   targetId = Long.parseLong(req[1]);
    String username = req[2];

    DatabaseManager.denyMemberRequest(reqId);

    // Редактируем сообщение с кнопками — убираем кнопки
    bot.editMessage(chatId, msgId,
            "❌ Отклонено\n\nЗапрос " + username + " отклонён.\n" +
            "(отклонено администратором " + adminId + ")");

    bot.sendText(targetId,
            "ℹ️ Ваш запрос отклонён. Если считаете ошибкой — напишите через /feedback.");
}
```

---

### Итоговая схема работы запросов

```
Пользователь → отправляет запрос
        ↓
checkRequestSpam() — проверяем антиспам
        ↓
upsertMemberRequest()
    ├─ Новый запрос (id > 0)
    │       ↓
    │   Показываем: "Запрос отправлен"
    │   sendNotification(получатель, текст, кнопки Принять/Отклонить)
    │
    └─ Обновлён существующий pending (id < -1)
            ↓
        Показываем: "Запрос обновлён, уведомление уже отправлено"
        (не шлём повторное уведомление)

Администратор нажимает «Принять»
        ↓
getMemberRequestStatus(reqId)
    ├─ "pending" → обрабатываем
    │       ↓
    │   addMember() + approveMemberRequest()
    │   editMessage() — меняем текст уведомления, убираем кнопки
    │   sendText(пользователю) — "ваш запрос принят"
    │
    └─ "approved"/"denied" → уже обработан
            ↓
        editMessage() — "запрос уже был обработан", убираем кнопки
```

---

### Примечания

- **`editMessage` vs `editNav`:** `editNav` всегда редактирует nav-сообщение администратора (то, что он видит у себя в чате как главное меню). `editMessage` редактирует любое сообщение по его `messageId` — в данном случае сообщение с кнопками уведомления.

- **Почему счётчик спама не сбрасывается:** `checkRequestSpam()` работает с таблицей `request_spam` отдельно от `member_requests`. Обновление запроса в `upsertMemberRequest()` не влияет на счётчик — он всегда растёт при каждом вызове `checkRequestSpam()`.

- **Несколько администраторов:** если уведомление о запросе получают несколько человек (например, через `/requests`), каждое сообщение с кнопками — отдельный объект в чате каждого администратора. После нажатия в чате одного администратора, в чате другого кнопки **останутся** — Telegram не позволяет редактировать сообщения в чужих чатах. Проверка статуса через `getMemberRequestStatus()` защищает от двойной обработки: второй администратор увидит "запрос уже обработан" при нажатии.
