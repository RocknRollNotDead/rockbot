package com.rockbot.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager — единственный класс, работающий с SQLite.
 *
 * Как работает JDBC:
 *  1. DriverManager.getConnection("jdbc:sqlite:rockbot.db") — открывает файл БД.
 *  2. conn.prepareStatement(sql) — компилирует запрос; "?" — безопасный placeholder.
 *  3. ps.executeUpdate() — INSERT/UPDATE/DELETE; ps.executeQuery() — SELECT.
 *  4. try-with-resources — автоматически закрывает соединение.
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_URL = "jdbc:sqlite:rockbot.db";

    // ═══════════════════════════════════════════════════════════════════════
    // ИНИЦИАЛИЗАЦИЯ
    // ═══════════════════════════════════════════════════════════════════════

    public static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS songs (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    title         TEXT NOT NULL,
                    album         TEXT,
                    history       TEXT,
                    audio_file_id TEXT,
                    added_by      INTEGER NOT NULL,
                    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS lyrics (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    song_id    INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                    content    TEXT NOT NULL,
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chords (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    song_id    INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                    instrument TEXT NOT NULL DEFAULT 'гитара',
                    content    TEXT NOT NULL,
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS instrumentals (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    song_id     INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                    file_id     TEXT NOT NULL,
                    uploaded_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS band_members (
                    user_id  INTEGER PRIMARY KEY,
                    username TEXT,
                    added_by INTEGER NOT NULL,
                    added_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS feedback (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id    INTEGER NOT NULL,
                    username   TEXT,
                    message    TEXT NOT NULL,
                    is_read    INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            // Добавить рядом с CREATE TABLE feedback:
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS feedback_reads (
                    feedback_id INTEGER NOT NULL REFERENCES feedback(id) ON DELETE CASCADE,
                    reader_id   INTEGER NOT NULL,  -- user_id того, кто прочитал
                    read_at     TEXT NOT NULL DEFAULT (datetime('now')),
                    PRIMARY KEY (feedback_id, reader_id)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    title      TEXT NOT NULL,
                    venue      TEXT,
                    city       TEXT,
                    event_date TEXT NOT NULL,
                    ticket_url TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS news (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    title      TEXT NOT NULL,
                    body       TEXT NOT NULL,
                    created_by INTEGER NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS subscriptions (
                    user_id       INTEGER PRIMARY KEY,
                    username      TEXT,
                    subscribed_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS polls (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    question   TEXT NOT NULL,
                    is_active  INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS poll_options (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    poll_id     INTEGER NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
                    option_text TEXT NOT NULL,
                    votes       INTEGER NOT NULL DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS poll_votes (
                    poll_id   INTEGER NOT NULL,
                    user_id   INTEGER NOT NULL,
                    option_id INTEGER NOT NULL,
                    PRIMARY KEY (poll_id, user_id)
                )
            """);
            // ── АЛЬБОМЫ ───────────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS albums (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT NOT NULL UNIQUE,
                    created_by INTEGER NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            // Связь песня↔альбом с позицией (номером в альбоме)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS album_songs (
                    album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
                    song_id  INTEGER NOT NULL REFERENCES songs(id)  ON DELETE CASCADE,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (album_id, song_id)
                )
            """);
            // ── НАСТРОЙКИ (ключ-значение) ─────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);
            // ── ЗАПРОСЫ НА УЧАСТИЕ ────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS member_requests (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id    INTEGER NOT NULL,
                    username   TEXT,
                    message    TEXT,
                    status     TEXT NOT NULL DEFAULT 'pending',
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            // ── АНТИСПАМ ЗАПРОСОВ ─────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS request_spam (
                    user_id        INTEGER PRIMARY KEY,
                    username       TEXT,
                    requests_today INTEGER NOT NULL DEFAULT 0,
                    last_req_date  TEXT,          -- YYYY-MM-DD
                    block_until    TEXT,          -- ISO datetime
                    block_days     INTEGER NOT NULL DEFAULT 1
                )
            """);
            // ── РЕПЕТИЦИИ ─────────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rehearsals (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    date_text   TEXT NOT NULL,
                    description TEXT,
                    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);

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

            log.info("База данных инициализирована.");
        } catch (SQLException e) {
            log.error("Ошибка инициализации БД", e);
            throw new RuntimeException(e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // НАСТРОЙКИ
    // ═══════════════════════════════════════════════════════════════════════

    public static long getSettingLong(String key, long defaultValue) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Long.parseLong(rs.getString(1));
            }
        } catch (Exception e) { log.error("getSettingLong key={}", key, e); }
        return defaultValue;
    }

    public static void setSetting(String key, String value) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO settings (key, value) VALUES (?,?)")) {
            ps.setString(1, key); ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("setSetting key={}", key, e); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // УЧАСТНИКИ ГРУППЫ
    // ═══════════════════════════════════════════════════════════════════════

    public static boolean isMember(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM band_members WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { log.error("isMember", e); return false; }
    }

    public static boolean addMember(long userId, String username, long addedBy) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR IGNORE INTO band_members (user_id, username, added_by) VALUES (?,?,?)")) {
            ps.setLong(1, userId); ps.setString(2, username); ps.setLong(3, addedBy);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("addMember", e); return false; }
    }

    public static boolean removeMember(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM band_members WHERE user_id = ?")) {
            ps.setLong(1, userId); return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("removeMember", e); return false; }
    }

    /** Возвращает [[user_id, username, added_at], ...] */
    public static List<String[]> getAllMembers() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT user_id, username, added_at FROM band_members ORDER BY added_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("user_id")),
                rs.getString("username") != null ? rs.getString("username") : "id:" + rs.getLong("user_id"),
                rs.getString("added_at")});
        } catch (SQLException e) { log.error("getAllMembers", e); }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ЗАПРОСЫ НА УЧАСТИЕ + АНТИСПАМ
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Проверяет, заблокирован ли пользователь, и возвращает сообщение о блокировке.
     * Если не заблокирован — возвращает null и записывает попытку.
     * Если достигнуто 3 запроса за день — блокирует на block_days дней.
     *
     * @return null если разрешено, иначе строку с пояснением блокировки
     */
    public static String checkRequestSpam(long userId, String username) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            // Читаем или создаём запись
            String today = java.time.LocalDate.now().toString();
            int reqToday = 0; String blockUntil = null; int blockDays = 1;

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT requests_today, last_req_date, block_until, block_days FROM request_spam WHERE user_id=?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        blockUntil = rs.getString("block_until");
                        blockDays  = rs.getInt("block_days");
                        String lastDate = rs.getString("last_req_date");
                        // Сбрасываем счётчик если новый день
                        reqToday = today.equals(lastDate) ? rs.getInt("requests_today") : 0;
                    }
                }
            }

            // Проверяем блокировку
            if (blockUntil != null && !blockUntil.isBlank()) {
                java.time.LocalDateTime unblock = java.time.LocalDateTime.parse(blockUntil);
                if (java.time.LocalDateTime.now().isBefore(unblock)) {
                    return "🚫 Вы временно не можете отправлять запросы.\n" +
                           "Блокировка снимается: " + blockUntil.replace("T", " ").substring(0, 16);
                }
                // Блокировка истекла — сбрасываем
                blockUntil = null;
                reqToday   = 0;
            }

            // Увеличиваем счётчик
            reqToday++;

            String newBlockUntil = blockUntil;
            int    newBlockDays  = blockDays;

            if (reqToday >= 3) {
                // Блокируем на blockDays дней
                newBlockUntil = java.time.LocalDateTime.now()
                        .plusDays(blockDays)
                        .toString().substring(0, 19);
                newBlockDays  = blockDays * 2; // следующая блокировка вдвое длиннее
                reqToday      = 0;             // сбрасываем счётчик дня
                log.info("Пользователь {} заблокирован на {} дней", userId, blockDays);
            }

            // Сохраняем
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO request_spam (user_id, username, requests_today, last_req_date, block_until, block_days) " +
                "VALUES (?,?,?,?,?,?)")) {
                ps.setLong(1, userId); ps.setString(2, username);
                ps.setInt(3, reqToday); ps.setString(4, today);
                ps.setString(5, newBlockUntil); ps.setInt(6, newBlockDays);
                ps.executeUpdate();
            }

            if (newBlockUntil != null && !newBlockUntil.isBlank() && reqToday == 0) {
                return "🚫 Вы отправили слишком много запросов.\n" +
                       "Следующий запрос можно отправить: " + newBlockUntil.replace("T"," ");
            }
            return null; // разрешено
        } catch (Exception e) { log.error("checkRequestSpam", e); return null; }
    }

    /** Возвращает список заблокированных пользователей: [[user_id, username, block_until], ...] */
    public static List<String[]> getBlockedUsers() {
        List<String[]> result = new ArrayList<>();
        String now = java.time.LocalDateTime.now().toString().substring(0, 19);
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, username, block_until FROM request_spam " +
                "WHERE block_until IS NOT NULL AND block_until > ? ORDER BY block_until");
             ) {
            ps.setString(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{
                    String.valueOf(rs.getLong("user_id")),
                    rs.getString("username") != null ? rs.getString("username") : "id:" + rs.getLong("user_id"),
                    rs.getString("block_until").replace("T"," ").substring(0, 16)
                });
            }
        } catch (SQLException e) { log.error("getBlockedUsers", e); }
        return result;
    }

    /** Снимает блокировку с пользователя */
    public static boolean unblockUser(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE request_spam SET block_until=NULL, requests_today=0 WHERE user_id=?")) {
            ps.setLong(1, userId); return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("unblockUser", e); return false; }
    }

    /** Создаёт или обновляет запрос на участие. Возвращает id записи. */
    public static long insertMemberRequest(long userId, String username, String message) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO member_requests (user_id, username, message) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, username != null ? username : "id:" + userId);
            ps.setString(3, message);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
        } catch (SQLException e) { log.error("insertMemberRequest", e); return -1; }
    }

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

    /** Возвращает данные запроса: [id, user_id, username, message, status] или null */
    public static String[] getMemberRequest(long reqId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, user_id, username, message, status FROM member_requests WHERE id=?")) {
            ps.setLong(1, reqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{
                    String.valueOf(rs.getLong("id")),
                    String.valueOf(rs.getLong("user_id")),
                    rs.getString("username") != null ? rs.getString("username") : "",
                    rs.getString("message")  != null ? rs.getString("message")  : "",
                    rs.getString("status")
                };
            }
        } catch (SQLException e) { log.error("getMemberRequest", e); return null; }
    }

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

    /** Возвращает все ожидающие запросы: [[id, user_id, username, message], ...] */
    public static List<String[]> getPendingRequests() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, user_id, username, message FROM member_requests " +
                "WHERE status='pending' ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("user_id")),
                rs.getString("username") != null ? rs.getString("username") : "id:" + rs.getLong("user_id"),
                rs.getString("message")  != null ? rs.getString("message")  : ""
            });
        } catch (SQLException e) { log.error("getPendingRequests", e); }
        return result;
    }

    public static boolean approveMemberRequest(long reqId) {
        return updateRequestStatus(reqId, "approved");
    }
    public static boolean denyMemberRequest(long reqId) {
        return updateRequestStatus(reqId, "denied");
    }
    private static boolean updateRequestStatus(long reqId, String status) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE member_requests SET status=? WHERE id=?")) {
            ps.setString(1, status); ps.setLong(2, reqId); return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("updateRequestStatus", e); return false; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ПЕСНИ
    // ═══════════════════════════════════════════════════════════════════════

    public static long addSong(String title, String album, long addedBy) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO songs (title, album, added_by) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            ps.setString(2, album.isBlank() ? null : album);
            ps.setLong(3, addedBy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
        } catch (SQLException e) { log.error("addSong", e); return -1; }
    }

    /** Возвращает [id, title, album, history, audio_file_id] или null */
    public static String[] getSong(long songId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,title,album,history,audio_file_id FROM songs WHERE id=?")) {
            ps.setLong(1, songId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{
                    String.valueOf(rs.getLong("id")), rs.getString("title"),
                    rs.getString("album")         != null ? rs.getString("album")         : "",
                    rs.getString("history")       != null ? rs.getString("history")       : "",
                    rs.getString("audio_file_id") != null ? rs.getString("audio_file_id") : ""
                };
            }
        } catch (SQLException e) { log.error("getSong", e); return null; }
    }

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

    /** Возвращает [[id, title, album, audio_file_id], ...] */
    public static List<String[]> getAllSongs() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,title,album,audio_file_id FROM songs ORDER BY COALESCE(album,'яяя'),title");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")), rs.getString("title"),
                rs.getString("album")         != null ? rs.getString("album")         : "",
                rs.getString("audio_file_id") != null ? rs.getString("audio_file_id") : ""
            });
        } catch (SQLException e) { log.error("getAllSongs", e); }
        return result;
    }

    public static List<String[]> searchSongs(String query) {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,title,album,audio_file_id FROM songs WHERE title LIKE ? ORDER BY title")) {
            ps.setString(1, "%" + query + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{
                    String.valueOf(rs.getLong("id")), rs.getString("title"),
                    rs.getString("album")         != null ? rs.getString("album")         : "",
                    rs.getString("audio_file_id") != null ? rs.getString("audio_file_id") : ""
                });
            }
        } catch (SQLException e) { log.error("searchSongs", e); }
        return result;
    }

    public static boolean updateSongTitle(long id, String v)   { return updateField("songs","title",id,v); }
    public static boolean updateSongAlbum(long id, String v)   { return updateField("songs","album",id,v); }
    public static boolean updateSongHistory(long id, String v) { return updateField("songs","history",id,v); }
    public static boolean updateSongAudio(long id, String v)   { return updateField("songs","audio_file_id",id,v); }

    private static boolean updateField(String table, String field, long id, String value) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + table + " SET " + field + " = ? WHERE id = ?")) {
            ps.setString(1, value); ps.setLong(2, id); return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("updateField {}.{}", table, field, e); return false; }
    }

    public static boolean deleteSong(long songId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM songs WHERE id=?")) {
            ps.setLong(1, songId); return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("deleteSong", e); return false; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ТЕКСТЫ И АККОРДЫ
    // ═══════════════════════════════════════════════════════════════════════

    public static void saveLyrics(long songId, String content) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (PreparedStatement d = conn.prepareStatement("DELETE FROM lyrics WHERE song_id=?"))
            { d.setLong(1, songId); d.executeUpdate(); }
            try (PreparedStatement i = conn.prepareStatement(
                "INSERT INTO lyrics (song_id, content) VALUES (?,?)"))
            { i.setLong(1, songId); i.setString(2, content); i.executeUpdate(); }
        } catch (SQLException e) { log.error("saveLyrics", e); }
    }

    public static String getLyrics(long songId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT content FROM lyrics WHERE song_id=?")) {
            ps.setLong(1, songId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { log.error("getLyrics", e); return null; }
    }

    public static void saveChords(long songId, String instrument, String content) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (PreparedStatement d = conn.prepareStatement(
                "DELETE FROM chords WHERE song_id=? AND instrument=?"))
            { d.setLong(1, songId); d.setString(2, instrument); d.executeUpdate(); }
            try (PreparedStatement i = conn.prepareStatement(
                "INSERT INTO chords (song_id, instrument, content) VALUES (?,?,?)"))
            { i.setLong(1, songId); i.setString(2, instrument); i.setString(3, content); i.executeUpdate(); }
        } catch (SQLException e) { log.error("saveChords", e); }
    }

    /** Возвращает [[chord_id, instrument], ...] */
    public static List<String[]> getChordsList(long songId) {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, instrument FROM chords WHERE song_id=? ORDER BY id")) {
            ps.setLong(1, songId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{
                    String.valueOf(rs.getLong("id")), rs.getString("instrument")});
            }
        } catch (SQLException e) { log.error("getChordsList", e); }
        return result;
    }

    public static String getChordById(long chordId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT content FROM chords WHERE id=?")) {
            ps.setLong(1, chordId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { log.error("getChordById", e); return null; }
    }

    public static String getChordInstrument(long chordId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT instrument FROM chords WHERE id=?")) {
            ps.setLong(1, chordId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : ""; }
        } catch (SQLException e) { log.error("getChordInstrument", e); return ""; }
    }

    public static void saveInstrumental(long songId, String fileId) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (PreparedStatement d = conn.prepareStatement(
                "DELETE FROM instrumentals WHERE song_id=?"))
            { d.setLong(1, songId); d.executeUpdate(); }
            try (PreparedStatement i = conn.prepareStatement(
                "INSERT INTO instrumentals (song_id, file_id) VALUES (?,?)"))
            { i.setLong(1, songId); i.setString(2, fileId); i.executeUpdate(); }
        } catch (SQLException e) { log.error("saveInstrumental", e); }
    }

    public static String getInstrumentalFileId(long songId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT file_id FROM instrumentals WHERE song_id=?")) {
            ps.setLong(1, songId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { log.error("getInstrumentalFileId", e); return null; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // АЛЬБОМЫ
    // ═══════════════════════════════════════════════════════════════════════

    /** Возвращает true если в базе есть хотя бы один альбом */
    public static boolean hasAlbums() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM albums LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) { log.error("hasAlbums", e); return false; }
    }

    /** Возвращает [[id, name, songCount], ...] */
    public static List<String[]> getAllAlbums() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT a.id, a.name, COUNT(albs.song_id) as cnt " +
                "FROM albums a LEFT JOIN album_songs albs ON albs.album_id = a.id " +
                "GROUP BY a.id ORDER BY a.name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")), rs.getString("name"),
                String.valueOf(rs.getInt("cnt"))
            });
        } catch (SQLException e) { log.error("getAllAlbums", e); }
        return result;
    }

    /**
     * Создаёт альбом и добавляет в него песни в заданном порядке.
     * @param name      название альбома
     * @param songIds   упорядоченный список ID песен
     * @param createdBy userId создателя
     * @return id нового альбома или -1 при ошибке
     */
    public static long createAlbum(String name, java.util.List<Long> songIds, long createdBy) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            try {
                long albumId;
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO albums (name, created_by) VALUES (?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name); ps.setLong(2, createdBy); ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        albumId = keys.next() ? keys.getLong(1) : -1;
                    }
                }
                for (int i = 0; i < songIds.size(); i++) {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO album_songs (album_id, song_id, position) VALUES (?,?,?)")) {
                        ps.setLong(1, albumId); ps.setLong(2, songIds.get(i)); ps.setInt(3, i + 1);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
                return albumId;
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { log.error("createAlbum", e); return -1; }
    }

    /** Возвращает песни альбома по позиции: [[id, title, album_col, audio_file_id], ...] */
    public static List<String[]> getAlbumSongs(long albumId) {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT s.id, s.title, s.album, s.audio_file_id " +
                "FROM album_songs albs JOIN songs s ON s.id = albs.song_id " +
                "WHERE albs.album_id=? ORDER BY albs.position")) {
            ps.setLong(1, albumId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{
                    String.valueOf(rs.getLong("id")), rs.getString("title"),
                    rs.getString("album")         != null ? rs.getString("album")         : "",
                    rs.getString("audio_file_id") != null ? rs.getString("audio_file_id") : ""
                });
            }
        } catch (SQLException e) { log.error("getAlbumSongs", e); }
        return result;
    }

    /** Возвращает название альбома по id или null */
    public static String getAlbumName(long albumId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM albums WHERE id=?")) {
            ps.setLong(1, albumId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { log.error("getAlbumName", e); return null; }
    }



    public static boolean deleteAlbum(long albumId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM albums WHERE id = ?")) {
            ps.setLong(1, albumId); return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.error("deleteAlbum", e); return false; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ОБРАТНАЯ СВЯЗЬ
    // ═══════════════════════════════════════════════════════════════════════

    public static void saveFeedback(long userId, String username, String message) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO feedback (user_id,username,message) VALUES (?,?,?)")) {
            ps.setLong(1, userId); ps.setString(2, username); ps.setString(3, message);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("saveFeedback", e); }
    }

    public static List<String[]> getUnreadFeedback() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,username,message,created_at FROM feedback WHERE is_read=0 ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")), rs.getString("username"),
                rs.getString("message"), rs.getString("created_at")});
        } catch (SQLException e) { log.error("getUnreadFeedback", e); }
        return result;
    }

    public static void markAllFeedbackRead() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE feedback SET is_read=1 WHERE is_read=0")) { ps.executeUpdate(); }
        catch (SQLException e) { log.error("markAllFeedbackRead", e); }
    }
    // DatabaseManager.java
    /** Проверяет, включены ли автоматические уведомления о фидбеке */
    public static boolean isFeedbackNotifyEnabled() {
        // Читаем настройку; "1" = включено, "0" = выключено.
        // По умолчанию включено (defaultValue = 1).
        return getSettingLong("feedback_notify", 1L) == 1L;
    }

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

    // ═══════════════════════════════════════════════════════════════════════
    // КОНЦЕРТЫ
    // ═══════════════════════════════════════════════════════════════════════

    public static long addEvent(String date, String location, String description) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events (title, venue, event_date, ticket_url) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, location.isBlank() ? "Концерт" : location);
            ps.setString(2, location);
            ps.setString(3, date);
            ps.setString(4, description.isBlank() ? null : description);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
        } catch (SQLException e) { log.error("addEvent", e); return -1; }
    }

    /** Возвращает [[id, date, location, description], ...] */
    public static List<String[]> getUpcomingEvents() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, event_date, venue, ticket_url FROM events ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")),
                rs.getString("event_date") != null ? rs.getString("event_date") : "",
                rs.getString("venue")      != null ? rs.getString("venue")      : "",
                rs.getString("ticket_url") != null ? rs.getString("ticket_url") : ""
            });
        } catch (SQLException e) { log.error("getUpcomingEvents", e); }
        return result;
    }

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

    // ═══════════════════════════════════════════════════════════════════════
    // РЕПЕТИЦИИ
    // ═══════════════════════════════════════════════════════════════════════

    public static long addRehearsal(String date, String description) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rehearsals (date_text, description) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, date);
            ps.setString(2, description.isBlank() ? null : description);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
        } catch (SQLException e) { log.error("addRehearsal", e); return -1; }
    }

    /** Возвращает [[id, date_text, description], ...] */
    public static List<String[]> getUpcomingRehearsals() {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, date_text, description FROM rehearsals ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new String[]{
                String.valueOf(rs.getLong("id")), rs.getString("date_text"),
                rs.getString("description") != null ? rs.getString("description") : ""
            });
        } catch (SQLException e) { log.error("getUpcomingRehearsals", e); }
        return result;
    }

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

    // ═══════════════════════════════════════════════════════════════════════
    // НОВОСТИ И ПОДПИСКИ
    // ═══════════════════════════════════════════════════════════════════════

    public static long addNews(String title, String body, long createdBy) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO news (title,body,created_by) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title); ps.setString(2, body); ps.setLong(3, createdBy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
        } catch (SQLException e) { log.error("addNews", e); return -1; }
    }

    public static List<String[]> getRecentNews(int limit) {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT title,body,created_at FROM news ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{
                    rs.getString("title"), rs.getString("body"), rs.getString("created_at")});
            }
        } catch (SQLException e) { log.error("getRecentNews", e); }
        return result;
    }

    public static void subscribe(long userId, String username) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO subscriptions (user_id,username) VALUES (?,?)")) {
            ps.setLong(1, userId); ps.setString(2, username); ps.executeUpdate();
        } catch (SQLException e) { log.error("subscribe", e); }
    }

    public static void unsubscribe(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM subscriptions WHERE user_id=?")) {
            ps.setLong(1, userId); ps.executeUpdate();
        } catch (SQLException e) { log.error("unsubscribe", e); }
    }

    public static boolean isSubscribed(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM subscriptions WHERE user_id=?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { log.error("isSubscribed", e); return false; }
    }

    public static List<Long> getAllSubscriberIds() {
        List<Long> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM subscriptions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(rs.getLong(1));
        } catch (SQLException e) { log.error("getAllSubscriberIds", e); }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ОПРОСЫ
    // ═══════════════════════════════════════════════════════════════════════

    public static long createPoll(String question, java.util.List<String> options) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            try {
                long pollId;
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO polls (question) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, question); ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { pollId = k.next() ? k.getLong(1) : -1; }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE polls SET is_active=0 WHERE id!=?")) { ps.setLong(1, pollId); ps.executeUpdate(); }
                for (String opt : options) {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO poll_options (poll_id, option_text) VALUES (?,?)")) {
                        ps.setLong(1, pollId); ps.setString(2, opt); ps.executeUpdate();
                    }
                }
                conn.commit(); return pollId;
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { log.error("createPoll", e); return -1; }
    }

    public static String[] getActivePoll() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,question FROM polls WHERE is_active=1 ORDER BY created_at DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{String.valueOf(rs.getLong("id")), rs.getString("question")};
            }
        } catch (SQLException e) { log.error("getActivePoll", e); return null; }
    }

    public static List<String[]> getPollResults(long pollId) {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,option_text,votes FROM poll_options WHERE poll_id=? ORDER BY id")) {
            ps.setLong(1, pollId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{
                    String.valueOf(rs.getLong("id")), rs.getString("option_text"),
                    String.valueOf(rs.getInt("votes"))});
            }
        } catch (SQLException e) { log.error("getPollResults", e); }
        return result;
    }

    public static boolean votePoll(long pollId, long userId, long optionId) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (PreparedStatement chk = conn.prepareStatement(
                "SELECT 1 FROM poll_votes WHERE poll_id=? AND user_id=?")) {
                chk.setLong(1, pollId); chk.setLong(2, userId);
                try (ResultSet rs = chk.executeQuery()) { if (rs.next()) return false; }
            }
            try (PreparedStatement i = conn.prepareStatement(
                "INSERT INTO poll_votes (poll_id,user_id,option_id) VALUES (?,?,?)")) {
                i.setLong(1, pollId); i.setLong(2, userId); i.setLong(3, optionId); i.executeUpdate();
            }
            try (PreparedStatement u = conn.prepareStatement(
                "UPDATE poll_options SET votes=votes+1 WHERE id=?")) {
                u.setLong(1, optionId); u.executeUpdate();
            }
            return true;
        } catch (SQLException e) { log.error("votePoll", e); return false; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ИМПОРТ ИЗ ДИРЕКТОРИИ
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Сканирует директорию и создаёт записи песен по именам аудиофайлов.
     * Формат: "Название.mp3" или "Название - Альбом.mp3"
     * Возвращает отчёт: [[fileName, status], ...]
     */
    public static List<String[]> importSongsFromDirectory(String dirPath, long addedBy) {
        List<String[]> results = new ArrayList<>();
        java.io.File dir = new java.io.File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            results.add(new String[]{"—", "❌ Директория не найдена: " + dirPath}); return results;
        }
        java.util.Set<String> exts = java.util.Set.of(".mp3",".ogg",".m4a",".wav",".flac",".aac",".opus");
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            results.add(new String[]{"—", "Директория пуста"}); return results;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
        int added = 0, skipped = 0;
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
            if (!exts.contains(ext)) continue;
            String base = name.substring(0, name.length() - ext.length()).trim();
            String title, album;
            if (base.contains(" - ")) {
                int sep = base.indexOf(" - "); title = base.substring(0, sep).trim(); album = base.substring(sep + 3).trim();
            } else { title = base; album = ""; }
            if (title.isBlank()) { results.add(new String[]{name, "Пропущено: пустое название"}); skipped++; continue; }
            if (songTitleExists(title)) { results.add(new String[]{name, "Пропущено: «" + title + "» уже есть"}); skipped++; continue; }
            long id = addSong(title, album, addedBy);
            if (id > 0) { results.add(new String[]{name, "✅ «" + title + "»" + (album.isBlank() ? "" : " / " + album)}); added++; }
            else        { results.add(new String[]{name, "❌ Ошибка сохранения «" + title + "»"}); }
        }
        results.add(new String[]{"—", "Итого: добавлено " + added + ", пропущено " + skipped});
        return results;
    }

    private static boolean songTitleExists(String title) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM songs WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // СТАТИСТИКА
    // ═══════════════════════════════════════════════════════════════════════

    public static String getDatabaseStats() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            int songs=0,audio=0,lyrics=0,chords=0,instr=0,feedback=0,events=0,subs=0,members=0,albums=0;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM songs"))                       { if(rs.next()) songs   =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM songs WHERE audio_file_id IS NOT NULL")) { if(rs.next()) audio  =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM lyrics"))                      { if(rs.next()) lyrics  =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chords"))                      { if(rs.next()) chords  =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM instrumentals"))               { if(rs.next()) instr   =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM feedback WHERE is_read=0"))    { if(rs.next()) feedback=rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM events"))                      { if(rs.next()) events  =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM subscriptions"))               { if(rs.next()) subs    =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM band_members"))                { if(rs.next()) members =rs.getInt(1); }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM albums"))                      { if(rs.next()) albums  =rs.getInt(1); }
            return String.format("📊 *Статистика*\n\n🎵 Песен: %d (аудио: %d)\n💿 Альбомов: %d\n📝 Текстов: %d\n🎸 Аккордов: %d\n🎼 Инструменталов: %d\n📩 Непрочитанных отзывов: %d\n📅 Концертов: %d\n🔔 Подписчиков: %d\n👥 Участников: %d",
                    songs,audio,albums,lyrics,chords,instr,feedback,events,subs,members);
        } catch (SQLException e) { log.error("getDatabaseStats",e); return "❌ Ошибка"; }
    }
}
