# Ответы на вопросы по доработке бота

## Содержание

1. [Имя и никнейм вместо ID пользователя](#1-имя-и-никнейм-вместо-id-пользователя)
2. [Изменить текст nav-сообщения при снятии кнопок](#2-изменить-текст-nav-сообщения-при-снятии-кнопок)
3. [Кнопки для каждого концерта, репетиции, альбома + удаление](#3-кнопки-для-каждого-концерта-репетиции-альбома--удаление)
4. [Убрать альбом из информации о песне](#4-убрать-альбом-из-информации-о-песне)
5. [Индивидуальный счётчик прочитанных фидбеков](#5-индивидуальный-счётчик-прочитанных-фидбеков)

---

## 1. Имя и никнейм вместо ID пользователя

### Что есть в объекте `User` от Telegram

Когда приходит сообщение или callback, Telegram передаёт объект `User` с полями:
- `getId()` — числовой ID (всегда есть)
- `getUserName()` — никнейм без `@` (может быть `null`, если не задан)
- `getFirstName()` — имя (всегда есть)
- `getLastName()` — фамилия (может быть `null`)

### Решение: утилитарный метод форматирования

Добавьте в `NavigationHandler.java` (или создайте отдельный класс `UserUtil`) метод:

```java
// NavigationHandler.java — рядом с методом esc()

/**
 * Форматирует отображаемое имя пользователя.
 * Результат: "Иван Петров (@ivanpetrov)" или "Иван (@ivanpetrov)" или "Иван Петров" или "id:123456"
 *
 * @param firstName  getFirstName() — всегда ненулевой
 * @param lastName   getLastName()  — может быть null
 * @param userName   getUserName()  — никнейм без @, может быть null
 */
public static String formatUser(String firstName, String lastName, String userName) {
    StringBuilder sb = new StringBuilder();

    // Имя
    if (firstName != null && !firstName.isBlank()) {
        sb.append(firstName.trim());
    }
    // Фамилия
    if (lastName != null && !lastName.isBlank()) {
        if (sb.length() > 0) sb.append(" ");
        sb.append(lastName.trim());
    }
    // Никнейм
    if (userName != null && !userName.isBlank()) {
        if (sb.length() > 0) sb.append(" ");
        sb.append("(@").append(userName.trim()).append(")");
    }
    // Фолбэк — ни имени, ни никнейма нет
    if (sb.length() == 0) sb.append("пользователь");

    return sb.toString();
}
```

### Где и как применять

**В `MessageHandler.java`** — при обработке входящего сообщения:

```java
// Сейчас:
String username = msg.getFrom().getUserName() != null
    ? msg.getFrom().getUserName() : "id:" + userId;

// Заменить на:
String displayName = NavigationHandler.formatUser(
    msg.getFrom().getFirstName(),
    msg.getFrom().getLastName(),
    msg.getFrom().getUserName()
);
```

Затем используйте `displayName` вместо `username` везде в `handle()` и `handleState()`.

**В `CallbackHandler.java`** — при обработке нажатий кнопок (`cbq.getFrom()`):

```java
// В handle():
String displayName = NavigationHandler.formatUser(
    cbq.getFrom().getFirstName(),
    cbq.getFrom().getLastName(),
    cbq.getFrom().getUserName()
);
```

**В уведомлении об запросе на участие** (`AWAIT_MEMBER_REQUEST_MSG`):

```java
// Вместо "user_" + userId:
String adminMsg = "📨 Новый запрос на участие\n\n" +
        "Пользователь: " + displayName + "\n" +
        "ID: " + userId + "\n" +
        (message.isBlank() ? "Без пояснения" : "Сообщение: " + message);
```

### Сохранение в базу данных

Там, где вы сохраняете `username` в таблицы (`band_members`, `member_requests`, `request_spam`), сохраняйте `displayName`:

```java
// В handleState(), case AWAIT_MEMBER_REQUEST_MSG:
long reqId = DatabaseManager.upsertMemberRequest(userId, displayName, message);

// В DatabaseManager.subscribe():
DatabaseManager.subscribe(userId, displayName);
```

> **Важно:** для корректного отображения `displayName` нужен доступ к объекту `User` из Telegram.
> В момент обработки `AWAIT_MEMBER_REQUEST_MSG` пользователь прислал сообщение — объект
> `msg.getFrom()` доступен. Сохраните `displayName` в `UserSession` на шаге начала диалога:
>
> ```java
> // При входе в состояние AWAIT_MEMBER_REQUEST_MSG:
> UserSession.set(userId, "displayName", displayName);
>
> // При сохранении:
> String name = UserSession.get(userId, "displayName");
> long reqId = DatabaseManager.upsertMemberRequest(userId, name, message);
> ```

---

## 2. Изменить текст nav-сообщения при снятии кнопок

### Почему текст остаётся

В `RockBandBot.stripNavKeyboard()` используется `EditMessageReplyMarkup` — этот метод меняет **только клавиатуру**, оставляя текст нетронутым.

### Решение: заменить `stripNavKeyboard` на `editNav` с новым текстом

Вместо отдельного метода `stripNavKeyboard()` используйте `editNav()` с нужным текстом и пустой клавиатурой.

**В `RockBandBot.java`** измените `stripNavKeyboard()`:

```java
/**
 * Убирает клавиатуру у nav-сообщения и заменяет текст на нейтральный.
 * Вызывается перед пересылкой nav в конец чата (resendNav).
 *
 * @param newText  текст для замены, например "🎸 Rock Band". Если null — оставляем старый текст.
 */
public void stripNavKeyboard(long chatId, long userId, String newText) {
    int oldMsgId = UserSession.getNavMessageId(userId);
    if (oldMsgId <= 0) return;

    if (newText != null) {
        // Меняем и текст, и убираем клавиатуру за один вызов
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(oldMsgId);
            edit.setText(newText);
            // Без parseMode — текст не содержит форматирования
            execute(edit);
        } catch (Exception e) {
            log.debug("stripNavKeyboard (text) chatId={} msgId={}: {}",
                      chatId, oldMsgId, e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
        }
    } else {
        // Убираем только клавиатуру
        try {
            EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
            edit.setChatId(chatId);
            edit.setMessageId(oldMsgId);
            edit.setReplyMarkup(new InlineKeyboardMarkup(List.of()));
            execute(edit);
        } catch (Exception e) {
            log.debug("stripNavKeyboard chatId={} msgId={}: {}",
                      chatId, oldMsgId, e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
        }
    }
}
```

Обновите `resendNav()`:

```java
public void resendNav(long chatId, long userId, String text, InlineKeyboardMarkup kbd) {
    // Передаём только название группы — нейтральный текст без "Выберите раздел"
    stripNavKeyboard(chatId, userId, "🎸 " + BotConfig.BAND_NAME);
    int newMsgId = sendWithKeyboard(chatId, text, kbd);
    if (newMsgId > 0) {
        UserSession.setNavMessageId(userId, newMsgId);
        UserSession.resetMsgsSinceNav(userId);
        UserSession.clearNavStale(userId);
    }
}
```

Также обновите вызовы `stripNavKeyboard()` в `cmdStart()` (`MessageHandler.java`):

```java
// Было:
bot.stripNavKeyboard(chatId, userId);

// Стало (передаём текст замены):
bot.stripNavKeyboard(chatId, userId, "🎸 " + BotConfig.BAND_NAME);
```

### Что увидит пользователь

```
До:   "🎸 Rock Band\n\nВаш статус: 🎧 Слушатель\n\nВыберите раздел:" [кнопки]
        ↓ (пользователь написал что-то, nav перемещается вниз)
После: "🎸 Rock Band"  ← без кнопок, без "Выберите раздел"
        ...новые сообщения...
        "🎸 Rock Band\n\nВаш статус: 🎧 Слушатель\n\nВыберите раздел:" [кнопки]  ← новое nav
```

---

## 3. Кнопки для каждого концерта, репетиции, альбома + удаление

### Общий подход

Сейчас все концерты/репетиции выводятся единым текстом. Нужно:
1. Добавить кнопку на каждый элемент (как уже сделано для песен)
2. По нажатию — показать карточку с кнопками «Удалить» и «Назад»
3. Подтверждение удаления и сам DELETE из базы

### Часть А: Концерты

#### Шаг 1. Новые callback_data в `Keyboards.java`

Добавьте в комментарий-документацию и создайте методы:

```java
// Новые callback:
//   ev_<id>       — карточка концерта
//   evd_<id>      — подтверждение удаления концерта
//   evdo_<id>     — удалить концерт

/** Список концертов как кнопки */
public static InlineKeyboardMarkup eventListWithButtons(List<String[]> events, boolean canAdd) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    for (String[] e : events) {
        // e = [id, date, location, description]
        String label = "📅 " + trunc(e[2], 22) + "  " + trunc(e[1], 16);
        rows.add(row(btn(label, "ev_" + e[0])));
    }
    if (canAdd) rows.add(row(btn("➕ Добавить концерт", "wiz_event")));
    rows.add(row(btn("🔙 Главное меню", "h")));
    return markup(rows);
}

/** Карточка концерта */
public static InlineKeyboardMarkup eventCard(long eventId, boolean canDelete) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    if (canDelete) rows.add(row(btn("🗑 Удалить", "evd_" + eventId)));
    rows.add(row(btn("🔙 К концертам", "ev")));
    return markup(rows);
}

/** Подтверждение удаления концерта */
public static InlineKeyboardMarkup eventDeleteConfirm(long eventId) {
    return markup(List.of(
        row(btn("✅ Да, удалить", "evdo_" + eventId), btn("❌ Отмена", "ev_" + eventId))
    ));
}
```

#### Шаг 2. SQL: удаление концерта в `DatabaseManager.java`

```java
public static boolean deleteEvent(long eventId) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement("DELETE FROM events WHERE id = ?")) {
        ps.setLong(1, eventId);
        return ps.executeUpdate() > 0;
    } catch (SQLException e) { log.error("deleteEvent", e); return false; }
}

/** Возвращает один концерт: [id, date, location, description] или null */
public static String[] getEvent(long eventId) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
             "SELECT id, event_date, venue, ticket_url FROM events WHERE id = ?")) {
        ps.setLong(1, eventId);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new String[]{
                String.valueOf(rs.getLong("id")),
                rs.getString("event_date") != null ? rs.getString("event_date") : "",
                rs.getString("venue")      != null ? rs.getString("venue")      : "",
                rs.getString("ticket_url") != null ? rs.getString("ticket_url") : ""
            };
        }
    } catch (SQLException e) { log.error("getEvent", e); return null; }
}
```

#### Шаг 3. Экран карточки концерта в `NavigationHandler.java`

```java
/** Показывает карточку одного концерта */
public void showEvent(long chatId, int msgId, long userId, long eventId) {
    String[] e = DatabaseManager.getEvent(eventId);
    if (e == null) { showEvents(chatId, msgId, userId); return; }

    StringBuilder t = new StringBuilder("📅 *Концерт*\n\n");
    t.append("📍 *").append(esc(e[2])).append("*\n");
    t.append("🗓 ").append(esc(e[1])).append("\n");
    if (!e[3].isBlank()) t.append("📋 ").append(esc(e[3]));

    smartNav(chatId, userId, msgId, t.toString(),
            Keyboards.eventCard(eventId, BotConfig.canEdit(userId)));
}
```

Измените `showEvents()` чтобы использовать кнопки вместо текстового списка:

```java
public void showEvents(long chatId, int msgId, long userId) {
    List<String[]> events = DatabaseManager.getUpcomingEvents();
    boolean canAdd = BotConfig.canEdit(userId);
    if (events.isEmpty()) {
        smartNav(chatId, userId, msgId, "📅 *Концерты*\n\nКонцертов пока нет.",
                Keyboards.eventListWithButtons(List.of(), canAdd));
        return;
    }
    // Текст — только заголовок, детали — в карточке по кнопке
    smartNav(chatId, userId, msgId,
            "📅 *Концерты*\n\nВыберите концерт:",
            Keyboards.eventListWithButtons(events, canAdd));
}
```

#### Шаг 4. Роутинг в `CallbackHandler.java`

```java
// В методе route():

// Карточка концерта: ev_<id>
if (data.startsWith("ev_") && !data.startsWith("evd_") && !data.startsWith("evdo_")) {
    nav.showEvent(chatId, msgId, userId, parseLong(data.substring(3)));
    return;
}
// Подтверждение удаления: evd_<id>
if (data.startsWith("evd_")) {
    if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
    long evId = parseLong(data.substring(4));
    String[] e = DatabaseManager.getEvent(evId);
    if (e == null) { nav.showEvents(chatId, msgId, userId); return; }
    bot.editNav(chatId, msgId,
            "🗑 Удалить концерт?\n\n📍 " + e[2] + "\n🗓 " + e[1],
            Keyboards.eventDeleteConfirm(evId));
    return;
}
// Удалить концерт: evdo_<id>
if (data.startsWith("evdo_")) {
    if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
    DatabaseManager.deleteEvent(parseLong(data.substring(5)));
    nav.showEvents(chatId, msgId, userId);
    return;
}
```

---

### Часть Б: Репетиции

Аналогично концертам — те же 4 шага. Callback-префиксы: `rh_<id>`, `rhd_<id>`, `rhdo_<id>`.

```java
// DatabaseManager.java
public static boolean deleteRehearsal(long id) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement("DELETE FROM rehearsals WHERE id = ?")) {
        ps.setLong(1, id); return ps.executeUpdate() > 0;
    } catch (SQLException e) { log.error("deleteRehearsal", e); return false; }
}

public static String[] getRehearsal(long id) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
             "SELECT id, date_text, description FROM rehearsals WHERE id = ?")) {
        ps.setLong(1, id);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new String[]{
                String.valueOf(rs.getLong("id")), rs.getString("date_text"),
                rs.getString("description") != null ? rs.getString("description") : ""
            };
        }
    } catch (SQLException e) { log.error("getRehearsal", e); return null; }
}
```

```java
// Keyboards.java
public static InlineKeyboardMarkup rehearsalListWithButtons(List<String[]> rh, boolean canAdd) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    for (String[] r : rh)
        rows.add(row(btn("🔸 " + trunc(r[1], 35), "rh_" + r[0])));
    if (canAdd) rows.add(row(btn("➕ Добавить репетицию", "wiz_rehearsal")));
    rows.add(row(btn("🔙 Главное меню", "h")));
    return markup(rows);
}

public static InlineKeyboardMarkup rehearsalCard(long id, boolean canDelete) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    if (canDelete) rows.add(row(btn("🗑 Удалить", "rhd_" + id)));
    rows.add(row(btn("🔙 К репетициям", "rh")));
    return markup(rows);
}

public static InlineKeyboardMarkup rehearsalDeleteConfirm(long id) {
    return markup(List.of(
        row(btn("✅ Да, удалить", "rhdo_" + id), btn("❌ Отмена", "rh_" + id))
    ));
}
```

```java
// NavigationHandler.java
public void showRehearsal(long chatId, int msgId, long userId, long rhId) {
    String[] r = DatabaseManager.getRehearsal(rhId);
    if (r == null) { showRehearsals(chatId, msgId, userId); return; }
    String t = "🎸 *Репетиция*\n\n🗓 *" + esc(r[1]) + "*\n" +
               (r[2].isBlank() ? "" : "\n" + esc(r[2]));
    smartNav(chatId, userId, msgId, t, Keyboards.rehearsalCard(rhId, BotConfig.canEdit(userId)));
}
```

---

### Часть В: Альбомы

Аналогично. Кнопка удаления альбома уже частично реализована. Callback-префиксы: `albd_<id>`, `albdo_<id>`.

```java
// DatabaseManager.java
public static boolean deleteAlbum(long albumId) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement("DELETE FROM albums WHERE id = ?")) {
        ps.setLong(1, albumId); return ps.executeUpdate() > 0;
    } catch (SQLException e) { log.error("deleteAlbum", e); return false; }
}
```

```java
// Keyboards.java — добавить кнопку удаления в albumSongList():
public static InlineKeyboardMarkup albumSongList(List<String[]> songs, long albumId, boolean canDelete) {
    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    for (String[] s : songs) {
        String icon = s[3].isBlank() ? "🔇" : "🎵";
        rows.add(row(btn(icon + " " + trunc(s[1], 32), "s_" + s[0])));
    }
    if (canDelete) rows.add(row(btn("🗑 Удалить альбом", "albd_" + albumId)));
    rows.add(row(btn("🔙 К альбомам", "alb")));
    return markup(rows);
}

public static InlineKeyboardMarkup albumDeleteConfirm(long albumId) {
    return markup(List.of(
        row(btn("✅ Да, удалить", "albdo_" + albumId), btn("❌ Отмена", "al_" + albumId))
    ));
}
```

---

## 4. Убрать альбом из информации о песне

### Что нужно изменить

1. При добавлении песни (`/addsong`, `wiz_song`) — не спрашивать альбом
2. В меню редактирования — убрать кнопку «💿 Альбом» (поле `album` в таблице `songs`)
3. В карточке песни — показывать альбом только если он есть, и только читаемо (без редактирования через карточку)
4. Добавление в альбом — только через раздел «Альбомы»

### Шаг 1. Убрать шаг AWAIT_SONG_ALBUM в `MessageHandler.java`

```java
// Было:
case AWAIT_SONG_TITLE -> {
    UserSession.set(userId, "title", text);
    UserSession.setState(userId, State.AWAIT_SONG_ALBUM);
    bot.editNav(chatId, navMsg, "💿 Из какого альбома? ...", Keyboards.inputCancel("h"));
}
case AWAIT_SONG_ALBUM -> {
    UserSession.set(userId, "album", ...);
    UserSession.setState(userId, State.AWAIT_SONG_AUDIO);
    bot.editNav(chatId, navMsg, "🎵 Отправьте аудиофайл...", Keyboards.inputCancel("h"));
}

// Стало — сразу просим аудиофайл:
case AWAIT_SONG_TITLE -> {
    UserSession.set(userId, "title", text);
    UserSession.setState(userId, State.AWAIT_SONG_AUDIO);
    bot.editNav(chatId, navMsg, "🎵 Отправьте *аудиофайл* (MP3 или OGG):", Keyboards.inputCancel("h"));
}
```

В `handleAudio()` уберите `UserSession.get(userId, "album")`:

```java
case AWAIT_SONG_AUDIO -> {
    String title = UserSession.get(userId, "title");
    // album больше не спрашиваем — передаём пустую строку
    long songId = DatabaseManager.addSong(title, "", userId);
    ...
}
```

Удалите `State.AWAIT_SONG_ALBUM` из `UserSession.State` (или оставьте — не критично).

### Шаг 2. Убрать кнопку «💿 Альбом» из меню редактирования в `Keyboards.java`

```java
// Было:
public static InlineKeyboardMarkup editMenu(long songId) {
    return markup(List.of(
        row(btn("📝 Название", "et_" + songId), btn("💿 Альбом", "ea_" + songId)),
        ...
    ));
}

// Стало — убираем btn("💿 Альбом", ...):
public static InlineKeyboardMarkup editMenu(long songId) {
    return markup(List.of(
        row(btn("📝 Название",     "et_"  + songId)),           // одна кнопка в ряду
        row(btn("🎵 Аудиофайл",   "eau_" + songId), btn("🎼 Инструментал", "ei_" + songId)),
        row(btn("📄 Текст песни",  "el_"  + songId), btn("🎸 Аккорды",     "ec_" + songId)),
        row(btn("📖 История",      "eh_"  + songId)),
        row(btn("🗑 Удалить",      "del_" + songId)),
        row(btn("🔙 К песне",      "s_"   + songId))
    ));
}
```

Также удалите обработку `"ea_"` из `CallbackHandler.route()` — она больше не нужна.

### Шаг 3. Показывать альбом в карточке только как читаемый текст

В `NavigationHandler.buildSongText()` уже реализовано правильно:

```java
// Сейчас (NavigationHandler.java):
private String buildSongText(String[] song) {
    StringBuilder t = new StringBuilder("🎸 *").append(esc(song[1])).append("*");
    if (!song[2].isBlank()) t.append("\n💿 ").append(esc(song[2])); // альбом только если есть
    ...
}
```

Но поле `song[2]` — это `album` из таблицы `songs`, которое мы больше не заполняем через диалог. Теперь альбом — это связь через таблицу `album_songs`. Нужно получать название альбома из неё:

```java
// DatabaseManager.java — новый метод
/**
 * Возвращает название альбома, в который входит эта песня.
 * Если песня не добавлена ни в один альбом — возвращает пустую строку.
 * (Если песня в нескольких альбомах — возвращает первый по алфавиту.)
 */
public static String getSongAlbumName(long songId) {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
             "SELECT a.name FROM albums a " +
             "JOIN album_songs als ON als.album_id = a.id " +
             "WHERE als.song_id = ? ORDER BY a.name LIMIT 1")) {
        ps.setLong(1, songId);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("name") : "";
        }
    } catch (SQLException e) { log.error("getSongAlbumName", e); return ""; }
}
```

Обновите `buildSongText()`:

```java
private String buildSongText(String[] song) {
    // song = [id, title, album_col, history, audio_file_id]
    StringBuilder t = new StringBuilder("🎸 *").append(esc(song[1])).append("*");

    // Берём альбом из таблицы album_songs, а не из поля songs.album
    String albumName = DatabaseManager.getSongAlbumName(Long.parseLong(song[0]));
    if (!albumName.isBlank()) {
        t.append("\n💿 ").append(esc(albumName));
    }
    // Если альбома нет — ничего не показываем

    t.append(!song[4].isBlank() ? "\n\n_🎵 Трек отправлен ниже_ ⬇️" : "\n\n_Аудиофайл не загружен_");
    return t.toString();
}
```

### Шаг 4. Убрать `album` из `getAllSongs()` (необязательно)

Поле `album` в таблице `songs` можно оставить пустым — оно не будет отображаться. Если хотите полностью убрать путаницу, в `getAllSongs()` просто не возвращайте это поле (или возвращайте пустую строку всегда).

---

## 5. Индивидуальный счётчик прочитанных/непрочитанных фидбеков

### Проблема

Сейчас таблица `feedback` имеет одно поле `is_read` — глобальное для всех. Как только один человек прочитал `/inbox`, все остальные тоже видят фидбек как прочитанный.

### Решение: таблица `feedback_reads`

Вместо поля `is_read` в `feedback` создаём отдельную таблицу, где для каждого сотрудника хранится факт прочтения каждого сообщения.

#### Шаг 1. Новая таблица в `DatabaseManager.initializeDatabase()`

```java
// Добавить рядом с CREATE TABLE feedback:
stmt.execute("""
    CREATE TABLE IF NOT EXISTS feedback_reads (
        feedback_id INTEGER NOT NULL REFERENCES feedback(id) ON DELETE CASCADE,
        reader_id   INTEGER NOT NULL,  -- user_id того, кто прочитал
        read_at     TEXT NOT NULL DEFAULT (datetime('now')),
        PRIMARY KEY (feedback_id, reader_id)
    )
""");
```

Поле `is_read` в таблице `feedback` можно оставить для совместимости — просто не использовать.

#### Шаг 2. Новые методы в `DatabaseManager.java`

```java
/**
 * Возвращает фидбек, который данный пользователь ещё НЕ прочитал.
 * [[id, username, message, created_at], ...]
 *
 * @param readerId  user_id участника/администратора, проверяющего inbox
 */
public static List<String[]> getUnreadFeedbackFor(long readerId) {
    List<String[]> result = new ArrayList<>();
    String sql = """
        SELECT f.id, f.username, f.message, f.created_at
        FROM feedback f
        WHERE f.id NOT IN (
            SELECT fr.feedback_id FROM feedback_reads fr WHERE fr.reader_id = ?
        )
        ORDER BY f.created_at
    """;
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, readerId);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")), rs.getString("username"),
                rs.getString("message"), rs.getString("created_at")
            });
        }
    } catch (SQLException e) { log.error("getUnreadFeedbackFor readerId={}", readerId, e); }
    return result;
}

/**
 * Отмечает все непрочитанные фидбеки как прочитанные для данного пользователя.
 * Другие пользователи не затрагиваются.
 */
public static void markFeedbackReadFor(long readerId) {
    // INSERT OR IGNORE — если запись уже есть, ничего не делаем
    String sql = """
        INSERT OR IGNORE INTO feedback_reads (feedback_id, reader_id)
        SELECT id, ? FROM feedback
        WHERE id NOT IN (
            SELECT feedback_id FROM feedback_reads WHERE reader_id = ?
        )
    """;
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, readerId);
        ps.setLong(2, readerId);
        ps.executeUpdate();
    } catch (SQLException e) { log.error("markFeedbackReadFor readerId={}", readerId, e); }
}

/**
 * Возвращает количество непрочитанных фидбеков для конкретного пользователя.
 * Используется для отображения счётчика (например, в /help или в меню).
 */
public static int getUnreadFeedbackCount(long readerId) {
    String sql = """
        SELECT COUNT(*) FROM feedback
        WHERE id NOT IN (SELECT feedback_id FROM feedback_reads WHERE reader_id = ?)
    """;
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, readerId);
        try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
    } catch (SQLException e) { log.error("getUnreadFeedbackCount", e); return 0; }
}
```

#### Шаг 3. Обновить `cmdInbox()` в `MessageHandler.java`

```java
private void cmdInbox(long chatId, long userId) { // добавляем userId как параметр
    // Получаем только непрочитанные ЭТИМ пользователем
    List<String[]> items = DatabaseManager.getUnreadFeedbackFor(userId);

    if (items.isEmpty()) {
        bot.sendText(chatId, "📩 Новых сообщений нет.");
        return;
    }

    StringBuilder sb = new StringBuilder("📩 *Непрочитанные (" + items.size() + ")*\n\n");
    for (String[] f : items) {
        sb.append("👤 ").append(f[1]).append(" | _").append(f[3]).append("_\n");
        sb.append(f[2]).append("\n───\n");
    }

    // Помечаем как прочитанные ТОЛЬКО для этого пользователя
    DatabaseManager.markFeedbackReadFor(userId);

    sendLong(chatId, sb.toString());
}
```

В `handleCommand()` обновите вызов:

```java
// Было:
case "/inbox" -> guardMember(userId, chatId, () -> cmdInbox(chatId));

// Стало (передаём userId):
case "/inbox" -> guardMember(userId, chatId, () -> cmdInbox(chatId, userId));
```

#### Шаг 4. Показывать счётчик непрочитанных в /help (опционально)

```java
// В cmdHelp(), в секцию для участников:
int unread = DatabaseManager.getUnreadFeedbackCount(userId);
String inboxLine = "/inbox — отзывы слушателей" +
        (unread > 0 ? " 📩 *(" + unread + " непрочит.)*" : "") + "\n";
```

### Схема работы

```
Слушатель → отправляет фидбек
    ↓
INSERT INTO feedback → id = 42

Участник A → /inbox
    SELECT WHERE id NOT IN (SELECT FROM feedback_reads WHERE reader_id = A)
    → видит фидбек 42
    ↓
    INSERT INTO feedback_reads (42, A)  ← помечаем прочитанным ТОЛЬКО для A

Участник B → /inbox
    SELECT WHERE id NOT IN (SELECT FROM feedback_reads WHERE reader_id = B)
    → всё равно видит фидбек 42 (записи (42, B) нет!)
    ↓
    INSERT INTO feedback_reads (42, B)  ← теперь и B прочитал
```

### Миграция: перенести старые is_read = 1

Если уже есть прочитанные фидбеки (по старому полю `is_read`), добавьте одноразовую миграцию после создания таблицы:

```java
// В initializeDatabase(), после CREATE TABLE feedback_reads:
// Помечаем все старые "прочитанные" записи как прочитанные для всех текущих участников
// (запустится один раз — INSERT OR IGNORE не дублирует)
try {
    stmt.execute("""
        INSERT OR IGNORE INTO feedback_reads (feedback_id, reader_id)
        SELECT f.id, m.user_id
        FROM feedback f
        CROSS JOIN band_members m
        WHERE f.is_read = 1
    """);
} catch (SQLException ignored) {
    // Таблица могла ещё не иметь данных — игнорируем
}
```
