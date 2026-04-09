package com.rockbot.handler;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db.DatabaseManager;
import com.rockbot.util.BotConfig;
import com.rockbot.util.Keyboards;
import com.rockbot.util.Role;
import com.rockbot.util.UserSession;
import com.rockbot.util.UserSession.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.Arrays;
import java.util.List;

/**
 * MessageHandler — команды, текстовый ввод, аудиофайлы.
 *
 * Правило счётчика: каждое входящее сообщение любого типа выполняет
 *   incMsgsSinceNav + markNavStale → при следующем showXxx nav пересылается вниз.
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
        long   chatId   = msg.getChatId();
        long   userId   = msg.getFrom().getId();
//        String username = msg.getFrom().getUserName() != null ? msg.getFrom().getUserName() : "id:" + userId;
        String displayName = NavigationHandler.formatUser(
                msg.getFrom().getFirstName(),
                msg.getFrom().getLastName(),
                msg.getFrom().getUserName()
        );

        // Любое входящее сообщение помечает nav устаревшим
        UserSession.incMsgsSinceNav(userId);
        UserSession.markNavStale(userId);

        if (msg.hasAudio()) { handleAudio(chatId, userId, msg.getAudio().getFileId()); return; }
        if (msg.hasDocument()) {
            String mime = msg.getDocument().getMimeType();
            if (mime != null && mime.startsWith("audio/")) { handleAudio(chatId, userId, msg.getDocument().getFileId()); return; }
        }
        if (!msg.hasText()) return;

        String text = msg.getText().trim();
        if (text.startsWith("/")) {
            String cmd = text.split("\\s+")[0].split("@")[0].toLowerCase();
            handleCommand(cmd, text, chatId, userId, displayName);
        } else {
            handleState(text, chatId, userId, msg);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // КОМАНДЫ
    // ════════════════════════════════════════════════════════════════════════

    private void handleCommand(String cmd, String fullText, long chatId, long userId, String username) throws Exception {
        // Обработка /cancel для состояний
        if (cmd.equals("/cancel")) {
            State currentState = UserSession.getState(userId);
            if (currentState == State.AWAIT_SET_BAND_INFO || currentState == State.AWAIT_ADD_BAND_INFO) {
                UserSession.clearState(userId);
                bot.sendText(chatId, "❌ Действие отменено.");
                return;
            }
            // Для других состояний продолжаем обычную обработку
        }
        
        switch (cmd) {

            // ── Все пользователи ──────────────────────────────────────────
            case "/start"        -> cmdStart(chatId, userId);
            case "/help"         -> cmdHelp(chatId, userId);
            case "/allsongs"     -> nav.showSongs(chatId, ensureNav(chatId, userId), userId, 0);
            case "/concerts"     -> nav.showEvents(chatId, ensureNav(chatId, userId), userId);
            case "/requestmember"-> nav.showMemberRequestInput(chatId, ensureNav(chatId, userId), userId);

            // /albums — только если есть альбомы или staff
            case "/albums" -> {
                if (DatabaseManager.hasAlbums() || BotConfig.canEdit(userId))
                    nav.showAlbums(chatId, ensureNav(chatId, userId), userId);
                else
                    bot.sendText(chatId, "💿 Альбомов пока нет.");
            }

            // /feedback — только слушатели
            case "/feedback"     -> cmdFeedback(chatId, userId);

            // /bandinfo — для всех пользователей
            case "/bandinfo"     -> cmdBandInfo(chatId, userId);

            // /subscribe — только если не подписан
            case "/subscribe" -> {
                if (DatabaseManager.isSubscribed(userId)) bot.sendText(chatId, "🔔 Вы уже подписаны.");
                else { DatabaseManager.subscribe(userId, username); bot.sendText(chatId, "🔔 Подписка оформлена!"); }
            }
            // /unsubscribe — только если подписан
            case "/unsubscribe" -> {
                if (!DatabaseManager.isSubscribed(userId)) bot.sendText(chatId, "ℹ️ Вы не подписаны.");
                else { DatabaseManager.unsubscribe(userId); bot.sendText(chatId, "🔕 Вы отписались."); }
            }

            // ── Участники и администраторы ─────────────────────────────────
            case "/addsong"      -> guardMemberNav(userId, chatId, () -> cmdAddSongViaNav(chatId, userId));
            case "/addalbum"     -> guardMemberNav(userId, chatId, () -> cmdAddAlbumViaNav(chatId, userId));
            case "/search"       -> guardMemberNav(userId, chatId, () -> nav.showSearchInput(chatId, ensureNav(chatId, userId), userId));
            case "/rehearsals"   -> guardMemberNav(userId, chatId, () -> nav.showRehearsals(chatId, ensureNav(chatId, userId), userId));
            case "/addevent"     -> guardMember(userId, chatId, () -> cmdAddEvent(chatId, userId));
            case "/addrehearsal" -> guardMember(userId, chatId, () -> cmdAddRehearsal(chatId, userId));
            case "/addnews"      -> guardMember(userId, chatId, () -> cmdAddNews(chatId, userId));
            case "/inbox"        -> guardMember(userId, chatId, () -> cmdInbox(chatId, userId));
            case "/createpoll"   -> guardMember(userId, chatId, () -> cmdCreatePoll(chatId, userId));
            case "/stats"        -> guardMember(userId, chatId, () -> bot.sendText(chatId, DatabaseManager.getDatabaseStats()));
            case "/togglefeedbacknotify" -> guardMember(userId, chatId, () -> cmdToggleFeedbackNotify(chatId));


            // ── Только администратор ───────────────────────────────────────
            case "/members"      -> guardAdmin(userId, chatId, () -> nav.showMemberList(chatId, ensureNav(chatId, userId), userId));
            case "/requests"     -> guardAdmin(userId, chatId, () -> cmdViewRequests(chatId, userId));
            case "/unblock"      -> guardAdmin(userId, chatId, () -> nav.showBlockedList(chatId, ensureNav(chatId, userId), userId));
            case "/setrecipient" -> guardAdmin(userId, chatId, () -> cmdSetRecipient(chatId, userId, fullText));
            case "/updateimport" -> guardAdmin(userId, chatId, () -> cmdUpdateImport(chatId, userId));
            case "/setbandinfo"  -> guardAdmin(userId, chatId, () -> cmdSetBandInfo(chatId, userId));
            case "/addbandinfo"  -> guardAdmin(userId, chatId, () -> cmdAddBandInfo(chatId, userId));
            // В handleCommand():

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

    private void handleState(String text, long chatId, long userId, Message msg) throws Exception {
        State state  = UserSession.getState(userId);
        int   navMsg = UserSession.getNavMessageId(userId);
        if (navMsg == 0 && state == State.NONE) return;
        // В handle():
        String displayName = NavigationHandler.formatUser(

                msg.getFrom().getFirstName(),
                msg.getFrom().getLastName(),
                msg.getFrom().getUserName()
        );
        switch (state) {

            case AWAIT_SEARCH ->
                nav.showSearchResults(chatId, navMsg, userId, text);

            // ── Добавление песни ──────────────────────────────────────────
            /*case AWAIT_SONG_TITLE -> {
                UserSession.set(userId, "title", text);
                UserSession.setState(userId, State.AWAIT_SONG_ALBUM);
                bot.editNav(chatId, navMsg,
                        "💿 Из какого альбома? _(напишите «нет» если без альбома)_",
                        Keyboards.inputCancel("h"));
            }
            case AWAIT_SONG_ALBUM -> {
                UserSession.set(userId, "album", text.equalsIgnoreCase("нет") ? "" : text);
                UserSession.setState(userId, State.AWAIT_SONG_AUDIO);
                bot.editNav(chatId, navMsg, "🎵 Отправьте *аудиофайл* (MP3 или OGG):", Keyboards.inputCancel("h"));
            }*/                     //стало
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
                // Переходим к интерактивному выбору песен
                nav.showAlbumSongPicker(chatId, navMsg, userId);
            }
            // AWAIT_ALBUM_SONGS обрабатывается через кнопки (ats_, albs, albc)

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
            case AWAIT_EDIT_INSTRUMENTAL_NAME -> {
                // Сохраняем название инструмента и запрашиваем файл
                UserSession.set(userId, "instrumentName", text);
                UserSession.setState(userId, State.AWAIT_EDIT_INSTRUMENTAL);
                long id = pLong(UserSession.get(userId, "songId"));
                bot.editNav(chatId, navMsg, "🎼 *Добавить инструментал*\n\nОтправьте *аудиофайл* для «" + NavigationHandler.esc(text) + "»:", Keyboards.backToEditMenu(id));
            }

            // ── Обратная связь (только слушатели) ────────────────────────
            /*case AWAIT_FEEDBACK -> {
                DatabaseManager.saveFeedback(userId, displayName, text);
                UserSession.clearState(userId);
                bot.editNav(chatId, navMsg, "💌 *Спасибо!* Ваше сообщение отправлено группе. 🤘", Keyboards.backToHome());
            }*/
            case AWAIT_FEEDBACK -> {
                // Сохраняем фидбек в базу
                DatabaseManager.saveFeedback(userId, displayName, text);
                UserSession.clearState(userId);
                bot.editNav(chatId, navMsg, "💌 Спасибо! Ваше сообщение отправлено группе. 🤘",
                        Keyboards.backToHome());

                // Автоматически уведомляем получателя, если функция включена
                if (DatabaseManager.isFeedbackNotifyEnabled()) {
                    long recipient = BotConfig.getRequestRecipient();
                    if (recipient > 0) {
                        // Текст БЕЗ Markdown — пользовательский текст может содержать * _ `
                        String notify = "💌 Новый фидбек от пользователя " + displayName + ":\n\n" + text;
                        bot.sendText(recipient, notify);
                    }
                }
            }

            // ── Запрос на участие ─────────────────────────────────────────
            case AWAIT_MEMBER_REQUEST_MSG -> {
                // Проверяем антиспам перед сохранением запроса
                String spamBlock = DatabaseManager.checkRequestSpam(userId, displayName);
                if (spamBlock != null) {
                    UserSession.clearState(userId);
                    bot.editNav(chatId, navMsg, spamBlock, Keyboards.backToHome());
                    return;
                }
                String message = text.equalsIgnoreCase("пропустить") ? "" : text;
                long reqId = DatabaseManager.upsertMemberRequest(userId, displayName, message);
                UserSession.clearState(userId);
                if (reqId < 0) { bot.editNav(chatId, navMsg, "❌ Не удалось отправить запрос.", Keyboards.backToHome()); return; }
                bot.editNav(chatId, navMsg, "📨 *Запрос отправлен!*\n\nАдминистраторы рассмотрят его.", Keyboards.backToHome());
                // Уведомляем ответственного
                long recipient = BotConfig.getRequestRecipient();
                if (recipient > 0) {
                    /*String adminMsg = "📨 *Новый запрос на участие*\n\n" +
                            "👤 user_" + userId + " (ID: `" + userId + "`)\n" +
                            (message.isBlank() ? "_Без пояснения_" : "💬 " + NavigationHandler.esc(message));*/
                    // Вместо "user_" + userId:
                    String adminMsg = "📨 Новый запрос на участие\n\n" +
                            "Пользователь: " + displayName + "\n" +
                            (message.isBlank() ? "Без пояснения" : "Сообщение: " + message);
                    bot.sendNotification(recipient, adminMsg, Keyboards.memberRequestActions(reqId));
                }
            }

            // ── Концерт (3 поля) ──────────────────────────────────────────
            case AWAIT_EVENT_DATE -> {
                UserSession.set(userId, "date", text);
                UserSession.setState(userId, State.AWAIT_EVENT_LOCATION);
                // Пишем прямо в nav
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
                    bot.broadcastToSubscribers("📢 *Новый концерт!*\n\n📍 *" + location + "*\n🗓 " + date + (desc.isBlank() ? "" : "\n\n" + desc));
                } else {
                    bot.editNav(chatId, navMsg, "❌ Не удалось сохранить концерт.", Keyboards.backToHome());
                }
            }

            // ── Репетиция (2 поля) ────────────────────────────────────────
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
                if (id > 0) { bot.sendText(chatId, "✅ Опубликовано!"); bot.broadcastToSubscribers("📰 *" + title + "*\n\n" + text); }
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

            // ── Смена получателя запросов ─────────────────────────────────
            case AWAIT_SET_RECIPIENT_ID -> {
                String username = text.trim();
                // Убираем @ если есть
                if (username.startsWith("@")) {
                    username = username.substring(1);
                }
                
                // Ищем пользователя по username в band_members
                Long targetId = DatabaseManager.getUserIdByUsername(username);
                if (targetId == null || targetId < 0) {
                    bot.sendText(chatId, "❌ Пользователь с username @" + username + " не найден среди участников группы.");
                    UserSession.clearState(userId);
                    return;
                }
                
                DatabaseManager.setSetting("request_recipient_id", String.valueOf(targetId));
                UserSession.clearState(userId);
                bot.sendText(chatId, "✅ Получатель запросов изменён на @" + username);
            }

            // ── Информация о группе ───────────────────────────────────────
            case AWAIT_SET_BAND_INFO -> {
                DatabaseManager.setBandInfo(text);
                UserSession.clearState(userId);
                bot.sendText(chatId, "✅ Информация о группе установлена.");
            }

            case AWAIT_ADD_BAND_INFO -> {
                DatabaseManager.addBandInfo(text);
                UserSession.clearState(userId);
                bot.sendText(chatId, "✅ Информация добавлена к существующей.");
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
                String title = UserSession.get(userId, "title"); //String album = UserSession.get(userId, "album");
                long songId  = DatabaseManager.addSong(title, "", userId);
                if (songId > 0) {
                    DatabaseManager.updateSongAudio(songId, fileId); UserSession.clearState(userId);
                    Role role = BotConfig.getRole(userId);
                    boolean isSub = DatabaseManager.isSubscribed(userId);
                    bot.editNav(chatId, navMsg,
                            "✅ Песня *" + NavigationHandler.esc(title) + "* добавлена! (id: `" + songId + "`)\n\nОткройте её для добавления текста, аккордов и истории.",
                            Keyboards.home(role != Role.LISTENER, role == Role.LISTENER, DatabaseManager.hasAlbums(), isSub));
                } else {
                    bot.editNav(chatId, navMsg, "❌ Не удалось сохранить.", Keyboards.inputCancel("h"));
                }
            }
            case AWAIT_EDIT_AUDIO -> {
                long id = pLong(UserSession.get(userId, "songId")); DatabaseManager.updateSongAudio(id, fileId);
                UserSession.clearState(userId); nav.showEditMenu(chatId, navMsg, userId, id);
            }
            case AWAIT_EDIT_INSTRUMENTAL -> {
                long id = pLong(UserSession.get(userId, "songId"));
                String instrumentName = UserSession.get(userId, "instrumentName");
                if (instrumentName == null || instrumentName.isBlank()) instrumentName = "инструментал";
                DatabaseManager.saveInstrumental(id, instrumentName, fileId);
                UserSession.clearState(userId); 
                nav.showEditMenu(chatId, navMsg, userId, id);
            }
            default -> bot.sendText(chatId, "Аудиофайл получен, но не ожидался.\n/addsong — добавить песню.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // РЕАЛИЗАЦИЯ КОМАНД
    // ════════════════════════════════════════════════════════════════════════

    private void cmdStart(long chatId, long userId) {
        Role role = BotConfig.getRole(userId);
        String roleName = switch (role) { case ADMIN -> "👑 Администратор"; case MEMBER -> "\uD83E\uDE95 Участник"; case LISTENER -> "🎧 Слушатель"; };
        String text = "\uD83D\uDE08 Добро пожаловать в бот группы *" + NavigationHandler.esc(BotConfig.BAND_NAME) + "* \uD83D\uDE08" + "\n\nВаш статус: " + roleName + "\n\nВыберите раздел:";

        boolean isStaff = role != Role.LISTENER; boolean isListener = role == Role.LISTENER;
        bot.stripNavKeyboard(chatId, userId, "Ваш статус: " + roleName);
        UserSession.resetMsgsSinceNav(userId); UserSession.clearNavStale(userId);
        int msgId = bot.sendWithKeyboard(chatId, text,
                Keyboards.home(isStaff, isListener, DatabaseManager.hasAlbums(), DatabaseManager.isSubscribed(userId)));
//        if (msgId > 0) UserSession.setNavMessageId(userId, msgId);
        if (msgId > 0) UserSession.setNavMessageId(userId, msgId);

    }

    /** /feedback — только для слушателей */
    private void cmdFeedback(long chatId, long userId) {
        if (BotConfig.getRole(userId) != Role.LISTENER) {
            bot.sendText(chatId, "ℹ️ /feedback доступен только слушателям."); return;
        }
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_FEEDBACK);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId),
                "💌 *Написать группе*\n\nОтправьте сообщение. Мы читаем всё! 🤘", Keyboards.inputCancel("h"));
    }

    private void cmdHelp(long chatId, long userId) {
        Role role = BotConfig.getRole(userId);
        boolean isListener = role == Role.LISTENER;
        boolean isStaff    = role != Role.LISTENER;
        boolean isAdmin    = role == Role.ADMIN;
        boolean isSub      = DatabaseManager.isSubscribed(userId);
        boolean hasAlbums  = DatabaseManager.hasAlbums();
//        System.out.println(hasAlbums);
        // В cmdHelp(), в секцию для участников:
        int unread = DatabaseManager.getUnreadFeedbackCount(userId);
        String inboxLine = "/inbox — отзывы слушателей" +
                (unread > 0 ? " 📩 *(" + unread + " непрочит.)*" : "") + "\n";

        String text = "*Навигация*\n/start — главное меню\n/bandinfo — вся информация о группе " + BotConfig.BAND_NAME + "\n/allsongs — все песни\n/search — поиск песен\n/concerts — концерты\n" +
                (hasAlbums/* || isStaff */? "/albums — альбомы\n" : "") +
                "\n*Уведомления*\n" +
                (!isSub ? "/subscribe — подписаться\n" : "") +
                (isSub  ? "/unsubscribe — отписаться\n" : "") +
                (isListener ? "\n*Связь*\n/feedback — написать группе\n/requestmember — запросить статус участника\n" : "") +
                (isStaff ? "\n*Управление контентом*\n"
                        + """
                        /addsong — добавить песню
                        /addalbum — собрать альбом
                        /addevent — добавить концерт
                        /addrehearsal — добавить репетицию
                        /rehearsals — список репетиций
                        /addnews — опубликовать новость
                        /createpoll — создать опрос
                        """ + inboxLine +
                        """
                        /stats — статистика
                        /togglefeedbacknotify — вкл/выкл автоуведомления о фидбеке
                        """ : "") +
//                         "/addsong\n/addalbum\n/addevent\n/addrehearsal\n/rehearsals\n/addnews\n/createpoll\n/inbox\n/stats\n" : "") +
                (isAdmin ? "\n*Администрирование*\n/members — участники группы\n/requests — запросы на участие\n/unblock — заблокированные пользователи\n/setrecipient — кто получает уведомления о запросах\n/setbandinfo — установить информацию о группе\n/addbandinfo — добавляет текст к существующей информации о группе\n/updateimport — импорт песен из директории\n" : "");
        bot.sendText(chatId, text);
    }

    private void cmdAddSongViaNav(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_SONG_TITLE);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId), "🎵 *Добавление песни*\n\nНазвание:", Keyboards.inputCancel("h"));
    }

    private void cmdAddAlbumViaNav(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_ALBUM_NAME);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId), "💿 *Добавление альбома*\n\nНазвание альбома:", Keyboards.inputCancel("h"));
    }

    private void cmdAddEvent(long chatId, long userId) {
        // Используем nav если возможно
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_EVENT_DATE);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId),
                "📅 *Добавление концерта*\n\n🗓 Дата и время (любой формат):", Keyboards.inputCancel("ev"));
    }

    private void cmdAddRehearsal(long chatId, long userId) {
        int navMsg = ensureNav(chatId, userId);
        UserSession.setState(userId, State.AWAIT_REHEARSAL_DATE);
        nav.showHome(chatId, navMsg, userId);
        bot.editNav(chatId, UserSession.getNavMessageId(userId),
                "🎸 *Добавление репетиции*\n\n🗓 Дата и время (любой формат):", Keyboards.inputCancel("rh"));
    }

    private void cmdAddNews(long chatId, long userId) {
        UserSession.setState(userId, State.AWAIT_NEWS_TITLE);
        bot.sendText(chatId, "📰 *Публикация новости*\n\nЗаголовок:");
    }

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
    private void cmdCreatePoll(long chatId, long userId) {
        UserSession.setState(userId, State.AWAIT_POLL_QUESTION);
        bot.sendText(chatId, "📊 *Создание опроса*\n\nВопрос:");
    }

    /** /requests — показывает все ожидающие запросы с кнопками принять/отклонить */
    /*private void cmdViewRequests(long chatId, long userId) {
        List<String[]> pending = DatabaseManager.getPendingRequests();
        if (pending.isEmpty()) { bot.sendText(chatId, "📩 Ожидающих запросов нет."); return; }
        for (String[] r : pending) {
            // r = [id, user_id, username, message]
            String msg = "📨 *Запрос на участие*\n\n👤 " + NavigationHandler.esc(r[2]) +
                    " (ID: `" + r[1] + "`)\n" +
                    (r[3].isBlank() ? "_Без пояснения_" : "💬 " + NavigationHandler.esc(r[3]));
            bot.sendNotification(chatId, msg, Keyboards.memberRequestActions(Long.parseLong(r[0])));
        }
    }*/
    /** /requests — показывает все ожидающие запросы с кнопками принять/отклонить */
    private void cmdViewRequests(long chatId, long userId) {
        List<String[]> pending = DatabaseManager.getPendingRequests();
        if (pending.isEmpty()) { bot.sendText(chatId, "📩 Ожидающих запросов нет."); return; }
        for (String[] r : pending) {
            // Текст без Markdown — sendNotification использует plain text
            String msg = "📨 Запрос на участие\n\n" +
                    "Пользователь: " + r[2] + "\n" +
                    (r[3].isBlank() ? "Без пояснения" : "Сообщение: " + r[3]);
            bot.sendNotification(chatId, msg, Keyboards.memberRequestActions(Long.parseLong(r[0])));
        }
    }



    /**
     * /setrecipient <username> — меняет получателя уведомлений о запросах.
     * Если username не указан — запрашивает его.
     */
    private void cmdSetRecipient(long chatId, long userId, String fullText) {
        String[] parts = fullText.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            UserSession.setState(userId, State.AWAIT_SET_RECIPIENT_ID);
            bot.sendText(chatId, "👤 Введите username администратора (например, @username или username), который будет получать запросы на участие:");
            return;
        }
        String username = parts[1].trim();
        // Убираем @ если есть
        if (username.startsWith("@")) {
            username = username.substring(1);
        }
        
        // Ищем пользователя по username в band_members
        Long targetId = DatabaseManager.getUserIdByUsername(username);
        if (targetId == null || targetId < 0) {
            bot.sendText(chatId, "❌ Пользователь с username @" + username + " не найден среди участников группы.");
            return;
        }
        
        DatabaseManager.setSetting("request_recipient_id", String.valueOf(targetId));
        bot.sendText(chatId, "✅ Получатель запросов изменён на @" + username);
    }

    /**
     * /updateimport — сканирует IMPORT_DIR из .env и добавляет новые песни.
     */
    private void cmdUpdateImport(long chatId, long userId) {
        String dirPath = BotConfig.IMPORT_DIR;
        if (dirPath.isBlank()) {
            bot.sendText(chatId, "❌ IMPORT_DIR не задана в файле .env.\n\nДобавьте строку:\nIMPORT_DIR=/путь/к/папке/с/музыкой");
            return;
        }
        bot.sendText(chatId, "🔍 Сканирую: `"/* + dirPath*/ + "`…");
        List<String[]> results = DatabaseManager.importSongsFromDirectory(dirPath, userId);
        StringBuilder sb = new StringBuilder("📂 *Результат импорта*\n\n");
        for (String[] r : results) sb.append(r[1]).append("\n");
        sendLong(chatId, sb.toString());
    }

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

    /** /bandinfo — показывает информацию о группе */
    private void cmdBandInfo(long chatId, long userId) {
        String info = DatabaseManager.getBandInfo();
        if (info.isBlank()) {
            bot.sendText(chatId, "ℹ️ Информация о группе пока не добавлена.");
        } else {
            sendLong(chatId, "ℹ️ *Информация о группе*\n\n" + info);
        }
    }

    /** /setbandinfo — устанавливает информацию о группе (заменяет полностью) */
    private void cmdSetBandInfo(long chatId, long userId) {
        UserSession.setState(userId, State.AWAIT_SET_BAND_INFO);
        bot.sendText(chatId, "ℹ️ *Установить информацию о группе*\n\nОтправьте текст, который заменит текущую информацию о группе.\n\nДля отмены напишите /cancel");
    }

    /** /addbandinfo — добавляет текст к существующей информации о группе */
    private void cmdAddBandInfo(long chatId, long userId) {
        UserSession.setState(userId, State.AWAIT_ADD_BAND_INFO);
        bot.sendText(chatId, "ℹ️ *Добавить информацию о группе*\n\nОтправьте текст, который будет добавлен к существующей информации.\n\nДля отмены напишите /cancel");
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
