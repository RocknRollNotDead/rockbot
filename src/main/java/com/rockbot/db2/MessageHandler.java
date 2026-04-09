package com.rockbot.db2;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db2.DatabaseManager;
import com.rockbot.util.BotConfig;
import com.rockbot.util.Keyboards;
import com.rockbot.util.Role;
import com.rockbot.util.UserSession;
import com.rockbot.util.UserSession.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Arrays;
import java.util.List;

/**
 * MessageHandler — команды, текстовый ввод, аудиофайлы.
 *
 * Каждое входящее сообщение:
 *  1. Вычисляет displayName пользователя (имя + никнейм)
 *  2. Сохраняет его в known_users (для последующего поиска по @username)
 *  3. Помечает nav устаревшим (incMsgsSinceNav + markNavStale)
 */
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);
    private final RockBandBot       bot;
    private final NavigationHandler nav;

    public MessageHandler(RockBandBot bot) { this.bot = bot; this.nav = new NavigationHandler(bot); }

    // ════════════════════════════════════════════════════════════════════════
    // ТОЧКА ВХОДА
    // ════════════════════════════════════════════════════════════════════════

    public void handle(Message msg) throws Exception {
        if (msg == null) return;
        long   chatId = msg.getChatId();
        User   from   = msg.getFrom();
        long   userId = from.getId();

        // Формируем читаемое имя: "Иван Петров (@ivanpetrov)" или "Иван (@ivanpetrov)" и т.п.
        String displayName = formatUser(from.getFirstName(), from.getLastName(), from.getUserName());

        // Сохраняем пользователя в known_users — нужно для /setrecipient @username
        DatabaseManager.upsertKnownUser(userId, displayName, from.getUserName());

        // Любое входящее сообщение помечает nav устаревшим
        UserSession.incMsgsSinceNav(userId);
        UserSession.markNavStale(userId);

        // Сохраняем displayName в сессии для использования в диалогах
        UserSession.set(userId, "displayName", displayName);

        if (msg.hasAudio()) { handleAudio(chatId, userId, msg.getAudio().getFileId()); return; }
        if (msg.hasDocument()) {
            String mime = msg.getDocument().getMimeType();
            if (mime != null && mime.startsWith("audio/")) {
                handleAudio(chatId, userId, msg.getDocument().getFileId()); return;
            }
        }
        if (!msg.hasText()) return;

        String text = msg.getText().trim();
        if (text.startsWith("/")) {
            String cmd = text.split("\\s+")[0].split("@")[0].toLowerCase();
            handleCommand(cmd, text, chatId, userId, displayName);
        } else {
            handleState(text, chatId, userId);
        }
    }

    /**
     * Формирует читаемое имя пользователя из данных Telegram.
     * Примеры:
     *   "Иван Петров (@ivanpetrov)"
     *   "Иван (@ivanpetrov)"
     *   "Иван Петров"
     *   "Пользователь"  — если нет ни имени, ни никнейма
     */
    public static String formatUser(String firstName, String lastName, String userName) {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) sb.append(firstName.trim());
        if (lastName  != null && !lastName.isBlank())  { if (sb.length() > 0) sb.append(" "); sb.append(lastName.trim()); }
        if (userName  != null && !userName.isBlank())  { if (sb.length() > 0) sb.append(" "); sb.append("(@").append(userName.trim()).append(")"); }
        return sb.length() > 0 ? sb.toString() : "Пользователь";
    }

    // ════════════════════════════════════════════════════════════════════════
    // КОМАНДЫ
    // ════════════════════════════════════════════════════════════════════════

    private void handleCommand(String cmd, String fullText, long chatId, long userId, String displayName) throws Exception {
        switch (cmd) {

            // ── Все пользователи ──────────────────────────────────────────
            case "/start"        -> cmdStart(chatId, userId);
            case "/help"         -> cmdHelp(chatId, userId);
            case "/allsongs"     -> nav.showSongs(chatId, ensureNav(chatId, userId), userId, 0);
            case "/concerts"     -> nav.showEvents(chatId, ensureNav(chatId, userId), userId);
            case "/requestmember"-> nav.showMemberRequestInput(chatId, ensureNav(chatId, userId), userId);

            case "/albums" -> {
                if (DatabaseManager.hasAlbums() || BotConfig.canEdit(userId))
                    nav.showAlbums(chatId, ensureNav(chatId, userId), userId);
                else bot.sendText(chatId, "💿 Альбомов пока нет.");
            }

            // /feedback — только слушатели
            case "/feedback"     -> cmdFeedback(chatId, userId);

            // /subscribe / /unsubscribe — сохраняем displayName
            case "/subscribe" -> {
                if (DatabaseManager.isSubscribed(userId)) bot.sendText(chatId, "🔔 Вы уже подписаны.");
                else { DatabaseManager.subscribe(userId, displayName); bot.sendText(chatId, "🔔 Подписка оформлена!"); }
            }
            case "/unsubscribe" -> {
                if (!DatabaseManager.isSubscribed(userId)) bot.sendText(chatId, "ℹ️ Вы не подписаны.");
                else { DatabaseManager.unsubscribe(userId); bot.sendText(chatId, "🔕 Вы отписались."); }
            }

            // ── Участники и администраторы ─────────────────────────────────
            case "/addsong"      -> guardMemberNav(userId, chatId, () -> cmdAddSongViaNav(chatId, userId));
            case "/addalbum"     -> guardMemberNav(userId, chatId, () -> cmdAddAlbumViaNav(chatId, userId));
            case "/rehearsals"   -> guardMemberNav(userId, chatId, () -> nav.showRehearsals(chatId, ensureNav(chatId, userId), userId));
            case "/addevent"     -> guardMember(userId, chatId, () -> cmdAddEvent(chatId, userId));
            case "/addrehearsal" -> guardMember(userId, chatId, () -> cmdAddRehearsal(chatId, userId));
            case "/addnews"      -> guardMember(userId, chatId, () -> cmdAddNews(chatId, userId));
            case "/inbox"        -> guardMember(userId, chatId, () -> cmdInbox(chatId));
            case "/createpoll"   -> guardMember(userId, chatId, () -> cmdCreatePoll(chatId, userId));
            case "/stats"        -> guardMember(userId, chatId, () -> bot.sendText(chatId, DatabaseManager.getDatabaseStats()));

            // ── Только администратор ───────────────────────────────────────
            case "/members"      -> guardAdmin(userId, chatId, () -> nav.showMemberList(chatId, ensureNav(chatId, userId), userId));
            case "/requests"     -> guardAdmin(userId, chatId, () -> cmdViewRequests(chatId));
            case "/unblock"      -> guardAdmin(userId, chatId, () -> nav.showBlockedList(chatId, ensureNav(chatId, userId), userId));
            // /setrecipient теперь принимает @username, а не числовой ID
            case "/setrecipient" -> guardAdmin(userId, chatId, () -> cmdSetRecipient(chatId, userId, fullText));
            case "/updateimport" -> guardAdmin(userId, chatId, () -> cmdUpdateImport(chatId, userId));

            default -> bot.sendText(chatId, "❓ Неизвестная команда. Напишите /help.");
        }
    }

    private void guardMemberNav(long userId, long chatId, ThrowingRunnable action) throws Exception {
        if (BotConfig.canEdit(userId)) action.run();
        else bot.sendText(chatId, "🔒 Только для участников группы.");
    }
    private void guardMember(long userId, long chatId, ThrowingRunnable action) throws Exception {
        if (BotConfig.canEdit(userId)) action.run();
        else bot.sendText(chatId, "🔒 Только для участников группы.");
    }
    private void guardAdmin(long userId, long chatId, ThrowingRunnable action) throws Exception {
        if (BotConfig.isAdmin(userId)) action.run();
        else bot.sendText(chatId, "🔒 Только для администраторов.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // СОСТОЯНИЯ (диалоги)
    // ════════════════════════════════════════════════════════════════════════

    private void handleState(String text, long chatId, long userId) throws Exception {
        State state  = UserSession.getState(userId);
        int   navMsg = UserSession.getNavMessageId(userId);
        if (navMsg == 0 && state == State.NONE) return;

        // displayName сохранён в сессии при обработке входящего сообщения
        String displayName = UserSession.get(userId, "displayName");
        if (displayName.isBlank()) displayName = "Пользователь";

        switch (state) {

            case AWAIT_SEARCH ->
                nav.showSearchResults(chatId, navMsg, userId, text);

            // ── Добавление песни ──────────────────────────────────────────
            case AWAIT_SONG_TITLE -> {
                UserSession.set(userId, "title", text);
                UserSession.setState(userId, State.AWAIT_SONG_AUDIO);
                bot.editNav(chatId, navMsg, "🎵 Отправьте *аудиофайл* (MP3 или OGG):", Keyboards.inputCancel("h"));
            }

            // ── Создание альбома ──────────────────────────────────────────
            case AWAIT_ALBUM_NAME -> {
                UserSession.set(userId, "albumName", text);
                UserSession.clearSelectedSongs(userId);
                UserSession.setState(userId, State.AWAIT_ALBUM_SONGS);
                nav.showAlbumSongPicker(chatId, navMsg, userId);
            }

            // ── Редактирование ────────────────────────────────────────────
            case AWAIT_EDIT_TITLE -> {
                long id = pLong(UserSession.get(userId, "songId"));
                DatabaseManager.updateSongTitle(id, text); UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            case AWAIT_EDIT_ALBUM -> {
                long id = pLong(UserSession.get(userId, "songId"));
                DatabaseManager.updateSongAlbum(id, text.equalsIgnoreCase("нет") ? "" : text); UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            case AWAIT_EDIT_LYRICS -> {
                long id = pLong(UserSession.get(userId, "songId"));
                DatabaseManager.saveLyrics(id, text); UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            case AWAIT_EDIT_CHORDS_INSTR -> {
                UserSession.set(userId, "instrument", text);
                UserSession.setState(userId, State.AWAIT_EDIT_CHORDS_TEXT);
                long id = pLong(UserSession.get(userId, "songId"));
                bot.editNav(chatId, navMsg, "🎸 Вставьте *текст аккордов* для «" + NavigationHandler.esc(text) + "»:", Keyboards.inputCancel("edt_" + id));
            }
            case AWAIT_EDIT_CHORDS_TEXT -> {
                long id = pLong(UserSession.get(userId, "songId"));
                DatabaseManager.saveChords(id, UserSession.get(userId, "instrument"), text); UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            case AWAIT_EDIT_HISTORY -> {
                long id = pLong(UserSession.get(userId, "songId"));
                DatabaseManager.updateSongHistory(id, text); UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }

            // ── Обратная связь (только слушатели) ────────────────────────
            case AWAIT_FEEDBACK -> {
                // Сохраняем displayName вместо "user_<id>"
                DatabaseManager.saveFeedback(userId, displayName, text);
                UserSession.clearState(userId);
                bot.editNav(chatId, navMsg, "💌 Спасибо! Ваше сообщение отправлено группе. 🤘", Keyboards.backToHome());
            }

            // ── Запрос на участие ─────────────────────────────────────────
            case AWAIT_MEMBER_REQUEST_MSG -> {
                // Антиспам проверяем с displayName — он выводится в /unblock
                String spamBlock = DatabaseManager.checkRequestSpam(userId, displayName);
                if (spamBlock != null) {
                    UserSession.clearState(userId);
                    bot.editNav(chatId, navMsg, spamBlock, Keyboards.backToHome());
                    return;
                }
                String message = text.equalsIgnoreCase("пропустить") ? "" : text;
                // Сохраняем displayName в запросе — он отобразится у получателя
                long reqId = DatabaseManager.insertMemberRequest(userId, displayName, message);
                UserSession.clearState(userId);
                if (reqId < 0) { bot.editNav(chatId, navMsg, "❌ Не удалось отправить запрос.", Keyboards.backToHome()); return; }
                bot.editNav(chatId, navMsg, "📨 Запрос отправлен!\n\nАдминистраторы рассмотрят его и ответят вам.", Keyboards.backToHome());
                // Уведомляем ответственного — показываем displayName, не ID
                long recipient = BotConfig.getRequestRecipient();
                if (recipient > 0) {
                    String adminMsg = "📨 Новый запрос на участие\n\n" +
                            "От: " + displayName + "\n" +
                            (message.isBlank() ? "Без пояснения" : "Сообщение: " + message);
                    bot.sendNotification(recipient, adminMsg, Keyboards.memberRequestActions(reqId));
                }
            }

            // ── Концерт ───────────────────────────────────────────────────
            case AWAIT_EVENT_DATE -> {
                UserSession.set(userId, "date", text);
                UserSession.setState(userId, State.AWAIT_EVENT_LOCATION);
                bot.editNav(chatId, navMsg, "📍 Место проведения:", Keyboards.inputCancel("ev"));
            }
            case AWAIT_EVENT_LOCATION -> {
                UserSession.set(userId, "location", text);
                UserSession.setState(userId, State.AWAIT_EVENT_DESC);
                bot.editNav(chatId, navMsg, "📋 Описание (или «нет»):", Keyboards.inputCancel("ev"));
            }
            case AWAIT_EVENT_DESC -> {
                String date = UserSession.get(userId, "date"); String location = UserSession.get(userId, "location");
                String desc = text.equalsIgnoreCase("нет") ? "" : text;
                long id = DatabaseManager.addEvent(date, location, desc);
                UserSession.clearState(userId);
                if (id > 0) {
                    bot.editNav(chatId, navMsg, "✅ Концерт добавлен!", Keyboards.backToHome());
                    bot.broadcastToSubscribers("📢 Новый концерт!\n\n📍 " + location + "\n🗓 " + date + (desc.isBlank() ? "" : "\n\n" + desc));
                } else {
                    bot.editNav(chatId, navMsg, "❌ Не удалось сохранить концерт.", Keyboards.backToHome());
                }
            }

            // ── Репетиция ─────────────────────────────────────────────────
            case AWAIT_REHEARSAL_DATE -> {
                UserSession.set(userId, "date", text);
                UserSession.setState(userId, State.AWAIT_REHEARSAL_DESC);
                bot.editNav(chatId, navMsg, "📋 Описание / план (или «нет»):", Keyboards.inputCancel("rh"));
            }
            case AWAIT_REHEARSAL_DESC -> {
                long id = DatabaseManager.addRehearsal(UserSession.get(userId, "date"), text.equalsIgnoreCase("нет") ? "" : text);
                UserSession.clearState(userId);
                bot.editNav(chatId, navMsg, id > 0 ? "✅ Репетиция добавлена!" : "❌ Ошибка.", Keyboards.backToHome());
            }

            // ── Новости ───────────────────────────────────────────────────
            case AWAIT_NEWS_TITLE -> {
                UserSession.set(userId, "title", text); UserSession.setState(userId, State.AWAIT_NEWS_BODY);
                bot.sendText(chatId, "✍️ Текст новости:");
            }
            case AWAIT_NEWS_BODY -> {
                String title = UserSession.get(userId, "title");
                long id = DatabaseManager.addNews(title, text, userId); UserSession.clearState(userId);
                if (id > 0) { bot.sendText(chatId, "✅ Опубликовано!"); bot.broadcastToSubscribers("📰 " + title + "\n\n" + text); }
                else bot.sendText(chatId, "❌ Ошибка публикации.");
            }

            // ── Опрос ─────────────────────────────────────────────────────
            case AWAIT_POLL_QUESTION -> {
                UserSession.set(userId, "question", text); UserSession.setState(userId, State.AWAIT_POLL_OPTIONS);
                bot.sendText(chatId, "📊 Варианты — каждый с новой строки (минимум 2):");
            }
            case AWAIT_POLL_OPTIONS -> {
                List<String> opts = Arrays.stream(text.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (opts.size() < 2) { bot.sendText(chatId, "❌ Нужно минимум 2 варианта."); return; }
                long pollId = DatabaseManager.createPoll(UserSession.get(userId, "question"), opts);
                UserSession.clearState(userId);
                bot.sendText(chatId, pollId > 0 ? "✅ Опрос создан!" : "❌ Ошибка.");
            }

            // ── Смена получателя запросов — вводится @username ────────────
            case AWAIT_SET_RECIPIENT_ID -> {
                // Принимаем @username или числовой ID как фолбэк
                String input = text.trim().replaceFirst("^@", ""); // убираем @ если есть
                long targetId = -1;

                // Сначала пробуем найти по username в known_users
                if (!input.isBlank() && !input.matches("\\d+")) {
                    targetId = DatabaseManager.findUserIdByUsername(input);
                    if (targetId < 0) {
                        bot.sendText(chatId, "❌ Пользователь @" + input + " не найден.\n\n" +
                                "Пользователь должен хотя бы раз написать боту, чтобы появиться в списке известных.");
                        UserSession.clearState(userId);
                        return;
                    }
                } else {
                    // Попробуем как числовой ID (фолбэк)
                    targetId = pLong(input);
                    if (targetId < 0) { bot.sendText(chatId, "❌ Неверный формат. Введите @username или числовой ID."); UserSession.clearState(userId); return; }
                }

                DatabaseManager.setSetting("request_recipient_id", String.valueOf(targetId));
                String recipientName = DatabaseManager.getDisplayName(targetId);
                UserSession.clearState(userId);
                bot.sendText(chatId, "✅ Получатель запросов изменён.\nТеперь это: " + recipientName);
            }

            default -> { /* текст вне диалога — игнорируем */ }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // АУДИОФАЙЛЫ
    // ════════════════════════════════════════════════════════════════════════

    private void handleAudio(long chatId, long userId, String fileId) {
        State state  = UserSession.getState(userId);
        int   navMsg = UserSession.getNavMessageId(userId);
        switch (state) {
            case AWAIT_SONG_AUDIO -> {
                String title = UserSession.get(userId, "title");
                long songId  = DatabaseManager.addSong(title, "", userId); // album всегда пустой
                if (songId > 0) {
                    DatabaseManager.updateSongAudio(songId, fileId); UserSession.clearState(userId);
                    Role role = BotConfig.getRole(userId);
                    bot.editNav(chatId, navMsg,
                            "✅ Песня " + NavigationHandler.esc(title) + " добавлена!\n\nОткройте её для добавления текста, аккордов и истории.",
                            Keyboards.home(role != Role.LISTENER, role == Role.LISTENER, DatabaseManager.hasAlbums(), DatabaseManager.isSubscribed(userId)));
                } else {
                    bot.editNav(chatId, navMsg, "❌ Не удалось сохранить.", Keyboards.inputCancel("h"));
                }
            }
            case AWAIT_EDIT_AUDIO -> {
                long id = pLong(UserSession.get(userId, "songId")); DatabaseManager.updateSongAudio(id, fileId);
                UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            case AWAIT_EDIT_INSTRUMENTAL -> {
                long id = pLong(UserSession.get(userId, "songId")); DatabaseManager.saveInstrumental(id, fileId);
                UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            default -> bot.sendText(chatId, "Аудиофайл получен, но не ожидался.\n/addsong — добавить песню.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // РЕАЛИЗАЦИЯ КОМАНД
    // ════════════════════════════════════════════════════════════════════════

    private void cmdStart(long chatId, long userId) {
        Role role = BotConfig.getRole(userId);
        String roleName = switch (role) { case ADMIN -> "👑 Администратор"; case MEMBER -> "🎸 Участник"; case LISTENER -> "🎧 Слушатель"; };
        String text = "🎸 " + BotConfig.BAND_NAME + "\n\nВаш статус: " + roleName + "\n\nВыберите раздел:";
        boolean isStaff = role != Role.LISTENER; boolean isListener = role == Role.LISTENER;
        bot.stripNavKeyboard(chatId, userId, null);
        UserSession.resetMsgsSinceNav(userId); UserSession.clearNavStale(userId);
        int msgId = bot.sendWithKeyboard(chatId, text,
                Keyboards.home(isStaff, isListener, DatabaseManager.hasAlbums(), DatabaseManager.isSubscribed(userId)));
        if (msgId > 0) UserSession.setNavMessageId(userId, msgId);
    }

    private void cmdFeedback(long chatId, long userId) {
        if (BotConfig.getRole(userId) != Role.LISTENER) {
            bot.sendText(chatId, "ℹ️ /feedback доступен только слушателям."); return;
        }
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_FEEDBACK);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId),
                "💌 Написать группе\n\nОтправьте сообщение. Мы читаем всё! 🤘", Keyboards.inputCancel("h"));
    }

    private void cmdHelp(long chatId, long userId) {
        Role role = BotConfig.getRole(userId);
        boolean isListener = role == Role.LISTENER;
        boolean isStaff    = role != Role.LISTENER;
        boolean isAdmin    = role == Role.ADMIN;
        boolean isSub      = DatabaseManager.isSubscribed(userId);
        boolean hasAlbums  = DatabaseManager.hasAlbums();

        String text = "Навигация\n/start — главное меню\n/allsongs — все песни\n/concerts — концерты\n" +
                (hasAlbums || isStaff ? "/albums — альбомы\n" : "") +
                "\nУведомления\n" +
                (!isSub ? "/subscribe — подписаться\n" : "") +
                (isSub  ? "/unsubscribe — отписаться\n" : "") +
                (isListener ? "\nСвязь\n/feedback — написать группе\n/requestmember — запросить статус участника\n" : "") +
                (isStaff ? "\nУправление контентом\n/addsong\n/addalbum\n/addevent\n/addrehearsal\n/rehearsals\n/addnews\n/createpoll\n/inbox\n/stats\n" : "") +
                (isAdmin ? "\nАдминистрирование\n/members — участники группы\n/requests — запросы на участие\n/unblock — заблокированные пользователи\n/setrecipient — кто получает уведомления о запросах\n/updateimport — импорт песен из директории\n" : "");
        bot.sendText(chatId, text);
    }

    private void cmdAddSongViaNav(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_SONG_TITLE);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId), "🎵 Добавление песни\n\nНазвание:", Keyboards.inputCancel("h"));
    }

    private void cmdAddAlbumViaNav(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_ALBUM_NAME);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId), "💿 Добавление альбома\n\nНазвание альбома:", Keyboards.inputCancel("h"));
    }

    private void cmdAddEvent(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_EVENT_DATE);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId),
                "📅 Добавление концерта\n\n🗓 Дата и время (любой формат):", Keyboards.inputCancel("ev"));
    }

    private void cmdAddRehearsal(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_REHEARSAL_DATE);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId),
                "🎸 Добавление репетиции\n\n🗓 Дата и время (любой формат):", Keyboards.inputCancel("rh"));
    }

    private void cmdAddNews(long chatId, long userId) {
        UserSession.setState(userId, State.AWAIT_NEWS_TITLE);
        bot.sendText(chatId, "📰 Публикация новости\n\nЗаголовок:");
    }

    private void cmdInbox(long chatId) {
        List<String[]> items = DatabaseManager.getUnreadFeedback();
        if (items.isEmpty()) { bot.sendText(chatId, "📩 Новых сообщений нет."); return; }
        // Показываем displayName вместо "user_<id>"
        StringBuilder sb = new StringBuilder("📩 Непрочитанные (" + items.size() + ")\n\n");
        for (String[] f : items)
            sb.append("👤 ").append(f[1]).append(" | ").append(f[3]).append("\n").append(f[2]).append("\n───\n");
        DatabaseManager.markAllFeedbackRead();
        sendLong(chatId, sb.toString());
    }

    private void cmdCreatePoll(long chatId, long userId) {
        UserSession.setState(userId, State.AWAIT_POLL_QUESTION);
        bot.sendText(chatId, "📊 Создание опроса\n\nВопрос:");
    }

    /** /requests — ожидающие запросы с кнопками принять/отклонить. Показывает displayName. */
    private void cmdViewRequests(long chatId) {
        List<String[]> pending = DatabaseManager.getPendingRequests();
        if (pending.isEmpty()) { bot.sendText(chatId, "📩 Ожидающих запросов нет."); return; }
        for (String[] r : pending) {
            // r = [id, user_id, display_name, message]
            String msg = "📨 Запрос на участие\n\n" +
                    "От: " + r[2] + "\n" +
                    (r[3].isBlank() ? "Без пояснения" : "Сообщение: " + r[3]);
            bot.sendNotification(chatId, msg, Keyboards.memberRequestActions(Long.parseLong(r[0])));
        }
    }

    /**
     * /setrecipient @username — меняет получателя уведомлений.
     * Принимает @username (ищет в known_users) или числовой ID как фолбэк.
     * Если аргумент не указан — запрашивает его.
     */
    private void cmdSetRecipient(long chatId, long userId, String fullText) {
        String[] parts = fullText.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            UserSession.setState(userId, State.AWAIT_SET_RECIPIENT_ID);
            bot.sendText(chatId, "👤 Введите @username пользователя, который будет получать запросы на участие.\n\n" +
                    "Пользователь должен хотя бы раз написать боту.");
            return;
        }

        String input    = parts[1].trim().replaceFirst("^@", "");
        long   targetId = -1;

        if (input.matches("\\d+")) {
            // Числовой ID — принимаем напрямую
            targetId = pLong(input);
        } else {
            // Username — ищем в known_users
            targetId = DatabaseManager.findUserIdByUsername(input);
            if (targetId < 0) {
                bot.sendText(chatId, "❌ Пользователь @" + input + " не найден.\n\n" +
                        "Пользователь должен хотя бы раз написать боту.");
                return;
            }
        }

        DatabaseManager.setSetting("request_recipient_id", String.valueOf(targetId));
        String recipientName = DatabaseManager.getDisplayName(targetId);
        bot.sendText(chatId, "✅ Получатель запросов изменён.\nТеперь это: " + recipientName);
    }

    private void cmdUpdateImport(long chatId, long userId) {
        String dirPath = BotConfig.IMPORT_DIR;
        if (dirPath.isBlank()) {
            bot.sendText(chatId, "❌ IMPORT_DIR не задана в .env.\n\nДобавьте строку:\nIMPORT_DIR=/путь/к/папке");
            return;
        }
        bot.sendText(chatId, "🔍 Сканирую: " + dirPath + "…");
        List<String[]> results = DatabaseManager.importSongsFromDirectory(dirPath, userId);
        StringBuilder sb = new StringBuilder("📂 Результат импорта\n\n");
        for (String[] r : results) sb.append(r[1]).append("\n");
        sendLong(chatId, sb.toString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // УТИЛИТЫ
    // ════════════════════════════════════════════════════════════════════════

    private int ensureNav(long chatId, long userId) {
        int navMsg = UserSession.getNavMessageId(userId);
        if (navMsg == 0) { cmdStart(chatId, userId); navMsg = UserSession.getNavMessageId(userId); UserSession.markNavStale(userId); }
        return navMsg;
    }

    private void sendLong(long chatId, String text) {
        final int MAX = 3800;
        while (text.length() > MAX) {
            int cut = text.lastIndexOf('\n', MAX); if (cut < 0) cut = MAX;
            bot.sendText(chatId, text.substring(0, cut)); text = text.substring(cut).stripLeading();
        }
        if (!text.isBlank()) bot.sendText(chatId, text);
    }

    private static long pLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; } }

    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }
}
