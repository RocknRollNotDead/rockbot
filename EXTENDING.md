# Rock Band Bot — Архитектура, логика и расширение

## Содержание

1. [Архитектура проекта](#1-архитектура-проекта)
2. [Логика навигации (nav-сообщение)](#2-логика-навигации)
3. [Как изменить поля диалога (концерт, репетиция)](#3-изменение-полей-диалога)
4. [Как добавить новую команду](#4-добавление-новой-команды)
5. [Как добавить новую кнопку в меню](#5-добавление-новой-кнопки)
6. [Как добавить новый экран](#6-добавление-нового-экрана)
7. [Импорт песен из директории](#7-импорт-песен-из-директории)
8. [Как добавить поле к существующей таблице](#8-добавление-поля-к-таблице)
9. [Правила, которых нужно придерживаться](#9-правила)

---

## 1. Архитектура проекта

```
Main.java                          — запуск, инициализация БД и бота
bot/RockBandBot.java               — Telegram API: sendText, editNav, resendNav, sendAudioMsg
db/DatabaseManager.java            — ВЕСЬ SQL: таблицы, запросы, импорт
handler/
  NavigationHandler.java           — все «экраны» бота (showHome, showSongs, showSong, …)
  CallbackHandler.java             — роутинг нажатий inline-кнопок
  MessageHandler.java              — команды, текстовый ввод, аудиофайлы
util/
  Keyboards.java                   — все inline-клавиатуры в одном месте
  UserSession.java                 — состояния диалогов + nav-флаги
  BotConfig.java                   — конфигурация (.env), проверка ролей
  Role.java                        — ADMIN / MEMBER / LISTENER
```

### Поток данных

```
Пользователь → Telegram → onUpdateReceived()
                             │
                    ┌────────┴────────┐
               hasMessage()      hasCallbackQuery()
                    │                 │
            MessageHandler      CallbackHandler
                    │                 │
            handleCommand()      route(data)
            handleState()            │
            handleAudio()            │
                    └────────┬────────┘
                             │
                    NavigationHandler.showXxx()
                             │
                         smartNav()
                        ┌────┴────┐
                   navStale?   editNav()
                    || msgs>=2
                        │
                    resendNav()
```

---

## 2. Логика навигации

Бот использует **одно сообщение с кнопками** («nav-сообщение»). При нажатии кнопки оно редактируется, а не создаётся новое. Это убирает спам сообщениями.

### Два флага в UserSession

| Флаг | Когда меняется | Что означает |
|------|---------------|--------------|
| `navStale` | +true: любое входящее сообщение, любой `sendText()` | Nav "уехал вверх" — нужно переслать |
| `msgsSinceNav` | +1: то же самое; =0: после `resendNav()` | Сколько сообщений ниже nav |

### smartNav() — центральный метод

```java
// В NavigationHandler:
private void smartNav(long chatId, long userId, int msgId, String text, InlineKeyboardMarkup kbd) {
    if (UserSession.isNavStale(userId) || UserSession.getMsgsSinceNav(userId) >= 2) {
        bot.resendNav(chatId, userId, text, kbd);  // пересылаем nav в конец чата
    } else {
        bot.editNav(chatId, msgId, text, kbd);     // редактируем на месте
    }
}
```

**Итог:** после любой команды (`/help`, `/allsongs`, текст в диалоге, аудиофайл) nav автоматически пересылается в конец чата при следующем нажатии кнопки.

---

## 3. Изменение полей диалога

### Концерт (текущая схема: дата → место → описание)

Диалог в **MessageHandler.java**, метод `handleState()`:

```java
case AWAIT_EVENT_DATE -> {
    UserSession.set(userId, "date", text);
    UserSession.setState(userId, State.AWAIT_EVENT_LOCATION);
    bot.sendText(chatId, "📍 Место проведения:");
}
case AWAIT_EVENT_LOCATION -> {
    UserSession.set(userId, "location", text);
    UserSession.setState(userId, State.AWAIT_EVENT_DESC);
    bot.sendText(chatId, "📋 Описание или «нет»:");
}
case AWAIT_EVENT_DESC -> {
    // сохраняем
    DatabaseManager.addEvent(date, location, desc);
}
```

**Чтобы добавить поле, например «Ссылка на билеты»:**

1. В `UserSession.State` добавьте `AWAIT_EVENT_TICKET`

2. В `MessageHandler` добавьте шаг между `AWAIT_EVENT_DESC` и сохранением:
```java
case AWAIT_EVENT_DESC -> {
    UserSession.set(userId, "desc", text);
    UserSession.setState(userId, State.AWAIT_EVENT_TICKET);
    bot.sendText(chatId, "🎫 Ссылка на билеты (или «нет»):");
}
case AWAIT_EVENT_TICKET -> {
    String ticket = text.equalsIgnoreCase("нет") ? "" : text;
    DatabaseManager.addEvent(date, location, desc, ticket);
    UserSession.clearState(userId);
    bot.sendText(chatId, "✅ Концерт добавлен!");
}
```

3. В `DatabaseManager.addEvent()` добавьте параметр и SQL.

4. В таблице `events` добавьте колонку (или используйте существующую `ticket_url`).

### Репетиция (текущая схема: дата → описание)

Аналогично. Диалог в `handleState()`:

```java
case AWAIT_REHEARSAL_DATE -> {
    UserSession.set(userId, "date", text);
    UserSession.setState(userId, State.AWAIT_REHEARSAL_DESC);
    bot.sendText(chatId, "📋 Описание или «нет»:");
}
case AWAIT_REHEARSAL_DESC -> {
    DatabaseManager.addRehearsal(date, desc);
    UserSession.clearState(userId);
    bot.sendText(chatId, "✅ Репетиция добавлена!");
}
```

**Чтобы добавить поле «Место»:**

1. `UserSession.State`: добавьте `AWAIT_REHEARSAL_LOCATION`
2. В `cmdAddRehearsal()` поменяйте начальный state на `AWAIT_REHEARSAL_DATE`
3. Вставьте шаг `AWAIT_REHEARSAL_LOCATION` между датой и описанием
4. В `DatabaseManager.addRehearsal()` добавьте параметр `location`
5. В SQL и схеме таблицы `rehearsals` — добавьте колонку

---

## 4. Добавление новой команды

### Пример: `/randomsong` — показывает случайную песню

**Шаг 1.** Добавьте SQL-метод в `DatabaseManager.java`:

```java
/** Возвращает случайную песню [id, title, album, history, audio_file_id] */
public static String[] getRandomSong() {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
             "SELECT id,title,album,history,audio_file_id FROM songs ORDER BY RANDOM() LIMIT 1");
         ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        return new String[]{
            String.valueOf(rs.getLong("id")), rs.getString("title"),
            rs.getString("album") != null ? rs.getString("album") : "",
            rs.getString("history") != null ? rs.getString("history") : "",
            rs.getString("audio_file_id") != null ? rs.getString("audio_file_id") : ""
        };
    } catch (SQLException e) { log.error("getRandomSong", e); return null; }
}
```

**Шаг 2.** В `MessageHandler.handleCommand()` добавьте case:

```java
case "/randomsong" -> nav.showSong(chatId, ensureNav(chatId, userId), userId,
        Long.parseLong(DatabaseManager.getRandomSong()[0]));
```

**Шаг 3.** Добавьте в `/help` в `cmdHelp()`:

```java
// В строку для всех пользователей:
"/randomsong — случайная песня\n"
```

Готово. Команда доступна всем.

**Если команда только для участников** — оберните в `guardMember()`:

```java
case "/randomsong" -> guardMember(userId, chatId,
    () -> nav.showSong(chatId, ensureNav(chatId, userId), userId,
              Long.parseLong(DatabaseManager.getRandomSong()[0])));
```

---

## 5. Добавление новой кнопки

### Пример: кнопка «🎲 Случайная» в главном меню

**Шаг 1.** В `Keyboards.java`, метод `home()`:

```java
// Добавьте новый ряд
rows.add(row(btn("🎲 Случайная песня", "random_song")));
```

**Шаг 2.** В `CallbackHandler.route()` добавьте обработку:

```java
case "random_song" -> {
    String[] song = DatabaseManager.getRandomSong();
    if (song != null) nav.showSong(chatId, msgId, userId, Long.parseLong(song[0]));
    else bot.editNav(chatId, msgId, "Песен пока нет.", Keyboards.backToHome());
    return;
}
```

### Пример: кнопка в карточке песни

1. В `Keyboards.songDetail()` добавьте кнопку в нужный ряд:
```java
if (нужное_условие) r1.add(btn("🆕 Новая кнопка", "new_" + songId));
```

2. В `CallbackHandler.route()`:
```java
if (data.startsWith("new_")) {
    long songId = parseLong(data.substring(4));
    // ваша логика
    return;
}
```

### Правило формата callback_data

Telegram ограничивает `callback_data` **64 байтами**. Кириллица — 2 байта на символ. Используйте короткие ASCII-префиксы и числа:

```
✅  "new_42"          — 6 байт
✅  "alb_3"           — 5 байт
❌  "новая_кнопка_42" — 26 байт
```

Для длинных строк (названия альбомов) используйте числовые индексы из `UserSession.albumCache`, как сделано в `showAlbums()`.

---

## 6. Добавление нового экрана

### Пример: экран «О группе»

**Шаг 1.** Добавьте кнопку в `Keyboards.home()`:
```java
rows.add(row(btn("ℹ️ О группе", "about")));
```

**Шаг 2.** Добавьте метод в `NavigationHandler.java`:
```java
public void showAbout(long chatId, int msgId, long userId) {
    String text = "ℹ️ *О группе " + esc(BotConfig.BAND_NAME) + "*\n\n" +
                  "Здесь можно написать всё о группе.";
    smartNav(chatId, userId, msgId, text, Keyboards.backToHome());
}
```

**Шаг 3.** В `CallbackHandler.route()`:
```java
case "about" -> { nav.showAbout(chatId, msgId, userId); return; }
```

**Шаг 4.** Задокументируйте в комментарии Keyboards.java:
```
 *   about           — экран «О группе»
```

---

## 7. Импорт песен из директории

### Команда `/importdir`

Доступна только администраторам. Сканирует папку на сервере/компьютере где запущен бот.

```
/importdir /home/music
```

**Поддерживаемые форматы:** `.mp3`, `.ogg`, `.m4a`, `.wav`, `.flac`, `.aac`, `.opus`

**Формат имён файлов:**

| Имя файла | Название песни | Альбом |
|-----------|---------------|--------|
| `Время.mp3` | Время | — |
| `Лето - Дебютный альбом.mp3` | Лето | Дебютный альбом |
| `01 - Intro - Первый альбом.mp3` | 01 - Intro | Первый альбом |

Разделитель между названием и альбомом — ` - ` (пробел, дефис, пробел). Первое вхождение разделителя — граница.

**Что происходит после импорта:**
- Создаются записи в таблице `songs` с названиями из файлов
- `audio_file_id` остаётся пустым — Telegram не умеет загружать файлы с диска автоматически
- Чтобы прикрепить аудио: откройте песню в `/allsongs` → ✏️ Редактировать → 🎵 Аудиофайл → отправьте файл

**Код импорта** находится в `DatabaseManager.importSongsFromDirectory()`.

---

## 8. Добавление поля к таблице

### Если база данных уже существует (rockbot.db)

SQLite поддерживает `ALTER TABLE ADD COLUMN`, но только с ограничениями:
- Нельзя добавить `NOT NULL` без `DEFAULT`
- Нельзя добавить уникальный индекс

**Вариант 1 (рекомендуется для разработки):** удалите `rockbot.db` и перезапустите бота. Все таблицы пересоздадутся с новой схемой.

**Вариант 2 (для продакшена, когда нельзя терять данные):**

Добавьте в `initializeDatabase()` после `CREATE TABLE`:
```java
// Добавляем новую колонку (безопасно запускать при каждом старте — повторный ALTER игнорируется)
try { stmt.execute("ALTER TABLE events ADD COLUMN ticket_url TEXT"); }
catch (SQLException ignored) { /* колонка уже есть */ }
```

### Пример: добавить поле `genre` к песням

1. В `initializeDatabase()` в `CREATE TABLE songs` добавьте:
```sql
genre TEXT,   -- жанр (Rock, Metal, Blues, …)
```

2. Добавьте метод `updateSongGenre()` в `DatabaseManager`:
```java
public static boolean updateSongGenre(long id, String genre) {
    return updateField("songs", "genre", id, genre);
}
```

3. Добавьте `AWAIT_EDIT_GENRE` в `UserSession.State`

4. Добавьте кнопку `"eg_" + songId` в `Keyboards.editMenu()`

5. В `CallbackHandler.route()`:
```java
if (data.startsWith("eg_")) {
    nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)),
            UserSession.State.AWAIT_EDIT_GENRE, "Введите *жанр* песни:");
    return;
}
```

6. В `MessageHandler.handleState()`:
```java
case AWAIT_EDIT_GENRE -> {
    long id = pLong(UserSession.get(userId, "songId"));
    DatabaseManager.updateSongGenre(id, text);
    UserSession.clearState(userId);
    nav.showEditMenu(chatId, navMsg, userId, id);
}
```

---

## 9. Правила

| Правило | Почему |
|---------|--------|
| Весь SQL только в `DatabaseManager` | Изолируем работу с БД, легко менять запросы |
| Все клавиатуры только в `Keyboards` | Один файл для всех кнопок, легко найти |
| Все экраны только в `NavigationHandler` | Логика UI не размазана по handler'ам |
| `bot.sendText()` ставит `navStale=true` автоматически | Не нужно помнить про флаги вручную |
| `callback_data` ≤ 64 байта, только ASCII + числа | Лимит Telegram |
| `editNav()` не меняет счётчики | Редактирование не создаёт новых сообщений |
| Состояния диалога хранятся только в `UserSession` | Не используйте статические переменные в handler'ах |
| Проверка ролей через `BotConfig.canEdit()` / `isAdmin()` | Не сравнивайте userId с константами в handler'ах |
| Все тексты пользователям на русском | Единый язык интерфейса |
| Комментарии к кнопкам и взаимодействиям | Объяснение нетривиальной логики |
