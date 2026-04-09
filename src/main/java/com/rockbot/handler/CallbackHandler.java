package com.rockbot.handler;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db.DatabaseManager;
import com.rockbot.util.BotConfig;
import com.rockbot.util.Keyboards;
import com.rockbot.util.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.List;

/** CallbackHandler — маршрутизирует нажатия inline-кнопок. */
public class CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);
    private final RockBandBot       bot;
    private final NavigationHandler nav;

    public CallbackHandler(RockBandBot bot) { this.bot = bot; this.nav = new NavigationHandler(bot); }

    public void handle(CallbackQuery cbq) {
        String data   = cbq.getData();
        long   chatId = cbq.getMessage().getChatId();
        int    msgId  = cbq.getMessage().getMessageId();
        long   userId = cbq.getFrom().getId();
        // В handle():
        String displayName = NavigationHandler.formatUser(
                cbq.getFrom().getFirstName(),
                cbq.getFrom().getLastName(),
                cbq.getFrom().getUserName()
        );
        ack(cbq.getId());
        log.debug("Callback userId={} data={}", displayName, data);
        try { route(data, chatId, msgId, userId, displayName/*, admName*/); }
        catch (Exception e) { log.error("Ошибка callback '{}': {}", data, e.getMessage(), e);
            bot.editNav(chatId, msgId, "❌ Произошла ошибка.", Keyboards.backToHome()); }
    }

    private void route(String data, long chatId, int msgId, long userId, String displayName/*, String displayAdmName*/) {

        // ── Точные совпадения ─────────────────────────────────────────────
        switch (data) {
            case "h"     -> { nav.showHome(chatId, msgId, userId);          return; }
            case "ev"    -> { nav.showEvents(chatId, msgId, userId);        return; }
            case "rh"    -> { nav.showRehearsals(chatId, msgId, userId);    return; }
            case "alb"   -> { nav.showAlbums(chatId, msgId, userId);        return; }
            case "srch"  -> { nav.showSearchInput(chatId, msgId, userId);   return; }
            case "req_member" -> { nav.showMemberRequestInput(chatId, msgId, userId); return; }
            case "mmb"   -> { nav.showMemberList(chatId, msgId, userId);    return; }
            case "ublk"  -> { nav.showBlockedList(chatId, msgId, userId);   return; }
            case "noop"  -> { return; }
            // Подписка/отписка прямо из главного меню
            case "sub"   -> {
                DatabaseManager.subscribe(userId, displayName);
                bot.editNav(chatId, msgId,
                        "🔔 Вы подписались на уведомления!",
                        Keyboards.backToHome());
                return;
            }
            case "unsub" -> {
                DatabaseManager.unsubscribe(userId);
                bot.editNav(chatId, msgId,
                        "🔕 Вы отписались от уведомлений.",
                        Keyboards.backToHome());
                return;
            }
            // Сохранить альбом
            case "albs"  -> { handleAlbumSave(chatId, msgId, userId);   return; }
            // Отменить создание альбома
            case "albc"  -> { UserSession.clearState(userId); nav.showHome(chatId, msgId, userId); return; }
        }

        // ── Список песен: sl_<page> ───────────────────────────────────────
        if (data.startsWith("sl_")) {
            nav.showSongs(chatId, msgId, userId, parseInt(data.substring(3), 0)); return;
        }

        // ── Альбом (просмотр): al_<albumId> ──────────────────────────────
        if (data.startsWith("al_")) {
            nav.showAlbumSongs(chatId, msgId, userId, parseLong(data.substring(3))); return;
        }

        // ── Карточка песни: s_<id> ────────────────────────────────────────
        if (data.startsWith("s_") && data.length() > 2) {
            long songId = parseLong(data.substring(2));
            if (songId > 0) { nav.showSong(chatId, msgId, userId, songId); return; }
        }

        // ── Возврат к песне без повторной отправки аудио: sback_<id> ─────
        if (data.startsWith("sback_")) {
            long songId = parseLong(data.substring(6));
            if (songId > 0) { nav.showSong(chatId, msgId, userId, songId, false); return; }
        }

        // ── Текст, аккорды, инструментал, история ────────────────────────
        if (data.startsWith("lyr_")) { nav.showLyrics(chatId, msgId, userId, parseLong(data.substring(4)));      return; }
        if (data.startsWith("cho_")) { nav.showChordsMenu(chatId, msgId, userId, parseLong(data.substring(4)));  return; }
        if (data.startsWith("ch_"))  {
            String[] p = data.split("_", 3);
            if (p.length == 3) nav.showChords(chatId, msgId, userId, parseLong(p[1]), parseLong(p[2]));
            return;
        }
        if (data.startsWith("ins_")) { nav.showInstrumental(chatId, msgId, userId, parseLong(data.substring(4))); return; }
        if (data.startsWith("his_")) { nav.showHistory(chatId, msgId, userId, parseLong(data.substring(4)));      return; }

        // ── Выбор конкретного инструментала для отправки ──────────────────
        if (data.startsWith("insp_")) {
            String[] parts = data.substring(5).split("_", 2);
            if (parts.length == 2) {
                long songId = parseLong(parts[0]);
                String instrumentName = parts[1];
                nav.sendInstrumental(chatId, msgId, userId, songId, instrumentName);
            }
            return;
        }

        // ── Меню редактирования ───────────────────────────────────────────
        if (data.startsWith("edt_")) { nav.showEditMenu(chatId, msgId, userId, parseLong(data.substring(4)));    return; }
        if (data.startsWith("et_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_TITLE,   "Введите новое *название*:"); return; }
        if (data.startsWith("ea_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_ALBUM,   "Введите *альбом* (или «нет»):"); return; }
        if (data.startsWith("eau_")) { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(4)), UserSession.State.AWAIT_EDIT_AUDIO,   "Отправьте новый *аудиофайл*:"); return; }
        if (data.startsWith("el_"))  {
            long id = parseLong(data.substring(3));
            String cur = DatabaseManager.getLyrics(id);
            String hint = cur != null ? "_Текущий текст (начало):_\n" + NavigationHandler.esc(cur.substring(0, Math.min(150, cur.length()))) + "…\n\n" : "";
            nav.showEditInput(chatId, msgId, userId, id, UserSession.State.AWAIT_EDIT_LYRICS, hint + "Вставьте *новый текст*:");
            return;
        }
        if (data.startsWith("ec_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_CHORDS_INSTR, "Для какого *инструмента*? (гитара / бас / пианино / …)"); return; }
        if (data.startsWith("ei_"))  { nav.showInstrumentalInput(chatId, msgId, userId, parseLong(data.substring(3))); return; }
        if (data.startsWith("eh_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_HISTORY,       "Напишите *историю создания*:"); return; }

        // ── Выбор типа инструментала ──────────────────────────────────────
        if (data.startsWith("instype_")) {
            String[] parts = data.substring(8).split("_", 2);
            if (parts.length == 2) {
                long songId = parseLong(parts[0]);
                String type = parts[1];
                if (type.equals("custom")) {
                    // Запрашиваем ввод названия инструмента
                    UserSession.set(userId, "songId", String.valueOf(songId));
                    UserSession.setState(userId, UserSession.State.AWAIT_EDIT_INSTRUMENTAL_NAME);
                    bot.editNav(chatId, msgId, "🎼 *Добавить инструментал*\n\nВведите название инструмента:", Keyboards.backToEditMenu(songId));
                } else {
                    // Используем предустановленное название
                    UserSession.set(userId, "songId", String.valueOf(songId));
                    UserSession.set(userId, "instrumentName", type);
                    UserSession.setState(userId, UserSession.State.AWAIT_EDIT_INSTRUMENTAL);
                    bot.editNav(chatId, msgId, "🎼 *Добавить инструментал*\n\nОтправьте *аудиофайл* для «" + type + "»:", Keyboards.backToEditMenu(songId));
                }
            }
            return;
        }

        // ── Удаление песни ────────────────────────────────────────────────
        if (data.startsWith("del_") && !data.startsWith("delok_")) {
            nav.showDeleteConfirm(chatId, msgId, userId, parseLong(data.substring(4))); return;
        }
        if (data.startsWith("delok_")) {
            long songId = parseLong(data.substring(6));
            if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            if (DatabaseManager.deleteSong(songId)) nav.showSongs(chatId, msgId, userId, UserSession.getLastSongsPage(userId));
            else bot.editNav(chatId, msgId, "❌ Песня не найдена.", Keyboards.backToHome());
            return;
        }

        // ── Одобрить/отклонить запрос ─────────────────────────────────────
        if (data.startsWith("mrok_")) { handleApproveRequest(chatId, msgId, userId, parseLong(data.substring(5)), displayName); return; }
        if (data.startsWith("mrno_")) { handleDenyRequest(chatId, msgId, userId, parseLong(data.substring(5)), displayName);    return; }

        // ── Участники: карточка, удаление ────────────────────────────────
        if (data.startsWith("mm_"))  { nav.showMemberCard(chatId, msgId, userId, parseLong(data.substring(3)));          return; }
        if (data.startsWith("mmd_")) { nav.showMemberDeleteConfirm(chatId, msgId, userId, parseLong(data.substring(4))); return; }
        if (data.startsWith("mmdo_")) {
            long targetId = parseLong(data.substring(5));
            if (!BotConfig.isAdmin(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            DatabaseManager.removeMember(targetId);
            nav.showMemberList(chatId, msgId, userId);
            return;
        }

        // ── Разблокировка ─────────────────────────────────────────────────
        if (data.startsWith("ublk_") && !data.startsWith("ublko_")) {
            nav.showUnblockConfirm(chatId, msgId, userId, parseLong(data.substring(5))); return;
        }
        if (data.startsWith("ublko_")) {
            long targetId = parseLong(data.substring(6));
            if (!BotConfig.isAdmin(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            DatabaseManager.unblockUser(targetId);
            nav.showBlockedList(chatId, msgId, userId);
            return;
        }

        // ── Переключение песни в альбоме: ats_<songId> ───────────────────
        if (data.startsWith("ats_")) {
            if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            long songId = parseLong(data.substring(4));
            // Переключаем выбор: если была — убираем, если не было — добавляем в конец
            UserSession.toggleSelectedSong(userId, songId);
            // Перестраиваем экран выбора (обновляем кнопки с новыми состояниями)
            nav.showAlbumSongPicker(chatId, msgId, userId);
            return;
        }

        // ── Wizards: запуск добавления из кнопок ─────────────────────────
        if (data.startsWith("wiz_")) {
            handleWizard(data.substring(4), chatId, msgId, userId); return;
        }

        // ── Голосование ───────────────────────────────────────────────────
        if (data.startsWith("vote_")) { handleVote(data, chatId, msgId, userId); return; }

        // ── Репетиции: карточка, удаление ─────────────────────────────────
        if (data.startsWith("rh_") && !data.startsWith("rhd_") && !data.startsWith("rhdo_")) {
            nav.showRehearsal(chatId, msgId, userId, parseLong(data.substring(3)));
            return;
        }
        if (data.startsWith("rhd_")) {
            if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            long rhId = parseLong(data.substring(4));
            String[] r = DatabaseManager.getRehearsal(rhId);
            if (r == null) { nav.showRehearsals(chatId, msgId, userId); return; }
            bot.editNav(chatId, msgId,
                    "🗑 Удалить репетицию?\n\n🗓 " + r[1] + (r[2].isBlank() ? "" : "\n" + r[2]),
                    Keyboards.rehearsalDeleteConfirm(rhId));
            return;
        }
        if (data.startsWith("rhdo_")) {
            if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            DatabaseManager.deleteRehearsal(parseLong(data.substring(5)));
            nav.showRehearsals(chatId, msgId, userId);
            return;
        }

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

        log.warn("Неизвестный callback: {}", data);
    }

    // ════════════════════════════════════════════════════════════════════════
    // WIZARD: запуск добавления контента из кнопок
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Запускает нужный мастер добавления прямо из nav-сообщения.
     * Вызывается при нажатии кнопок «➕ Добавить ...» в списках.
     */
    private void handleWizard(String type, long chatId, int msgId, long userId) {
        if (!BotConfig.canEdit(userId)) {
            bot.editNav(chatId, msgId, "🔒 Только для участников группы.", Keyboards.backToHome()); return;
        }
        switch (type) {
            case "song" -> {
                // Запускаем диалог добавления песни прямо в nav-сообщении
                UserSession.setState(userId, UserSession.State.AWAIT_SONG_TITLE);
                bot.editNav(chatId, msgId, "🎵 *Добавление песни*\n\nКак называется песня?",
                        Keyboards.inputCancel("h"));
            }
            case "album" -> {
                // Запускаем диалог добавления альбома
                UserSession.setState(userId, UserSession.State.AWAIT_ALBUM_NAME);
                bot.editNav(chatId, msgId, "💿 *Добавление альбома*\n\nНазвание альбома:",
                        Keyboards.inputCancel("h"));
            }
            case "event" -> {
                // Запускаем диалог концерта в nav
                UserSession.setState(userId, UserSession.State.AWAIT_EVENT_DATE);
                bot.editNav(chatId, msgId,
                        "📅 *Добавление концерта*\n\n🗓 Дата и время:\n",
                        Keyboards.inputCancel("ev"));
            }
            case "rehearsal" -> {
                // Запускаем диалог репетиции в nav
                UserSession.setState(userId, UserSession.State.AWAIT_REHEARSAL_DATE);
                bot.editNav(chatId, msgId,
                        "🎸 *Добавление репетиции*\n\n🗓 Дата и время:\n",
                        Keyboards.inputCancel("rh"));
            }
            default -> log.warn("Неизвестный wizard: {}", type);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // СОХРАНЕНИЕ АЛЬБОМА
    // ════════════════════════════════════════════════════════════════════════

    private void handleAlbumSave(long chatId, int msgId, long userId) {
        if (!BotConfig.canEdit(userId)) {
            bot.editNav(chatId, msgId, "🔒 Только для участников.", Keyboards.backToHome()); return;
        }
        String albumName = UserSession.get(userId, "albumName");
        List<Long> selected = UserSession.getSelectedSongs(userId);

        if (albumName.isBlank()) {
            bot.editNav(chatId, msgId, "❌ Название альбома не задано.", Keyboards.backToHome());
            UserSession.clearState(userId); return;
        }
        if (selected.isEmpty()) {
            // Разрешаем сохранить пустой альбом (можно добавить песни позже)
            long albumId = DatabaseManager.createAlbum(albumName, List.of(), userId);
            UserSession.clearState(userId);
            if (albumId > 0) {
                bot.editNav(chatId, msgId,
                        "✅ Альбом «" + NavigationHandler.esc(albumName) + "» создан (без песен).\n\n" +
                        "_Песни можно добавить через ✏️ Редактировать → 💿 Альбом_",
                        Keyboards.backToHome());
            } else {
                bot.editNav(chatId, msgId, "❌ Ошибка при создании альбома.", Keyboards.backToHome());
            }
            return;
        }

        long albumId = DatabaseManager.createAlbum(albumName, selected, userId);
        UserSession.clearState(userId);

        if (albumId > 0) {
            bot.editNav(chatId, msgId,
                    "✅ Альбом *" + NavigationHandler.esc(albumName) + "* создан!\n" +
                    "Песен: " + selected.size(),
                    Keyboards.backToHome());
        } else {
            bot.editNav(chatId, msgId,
                    "❌ Не удалось создать альбом. Возможно, альбом с таким названием уже существует.",
                    Keyboards.backToHome());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ЗАПРОСЫ НА УЧАСТИЕ
    // ════════════════════════════════════════════════════════════════════════

    /*private void handleApproveRequest(long chatId, int msgId, long adminId, long reqId) {
        if (!BotConfig.isAdmin(adminId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
        String[] req = DatabaseManager.getMemberRequest(reqId);
        if (req == null) { bot.editNav(chatId, msgId, "❌ Запрос не найден.", Keyboards.backToHome()); return; }
        long   targetId  = Long.parseLong(req[1]);
        String username  = req[2];
        DatabaseManager.addMember(targetId, username, adminId);
        DatabaseManager.approveMemberRequest(reqId);
        bot.editNav(chatId, msgId, "✅ *Принято*\n\nПользователь " + NavigationHandler.esc(username) + " добавлен как участник.", Keyboards.backToHome());
        bot.sendText(targetId, "🎉 *Ваш запрос принят!*\n\nВы теперь участник группы *" + NavigationHandler.esc(BotConfig.BAND_NAME) + "*.\nНажмите /start.");
    }

    private void handleDenyRequest(long chatId, int msgId, long adminId, long reqId) {
        if (!BotConfig.isAdmin(adminId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
        String[] req = DatabaseManager.getMemberRequest(reqId);
        if (req == null) { bot.editNav(chatId, msgId, "❌ Запрос не найден.", Keyboards.backToHome()); return; }
        long   targetId  = Long.parseLong(req[1]);
        String username  = req[2];
        DatabaseManager.denyMemberRequest(reqId);
        bot.editNav(chatId, msgId, "❌ *Отклонено*\n\nЗапрос " + NavigationHandler.esc(username) + " отклонён.", Keyboards.backToHome());
        bot.sendText(targetId, "ℹ️ Ваш запрос отклонён. Если считаете ошибкой — напишите через /feedback.");
    }*/

/*
    private void handleApproveRequest(long chatId, int msgId, long adminId, long reqId) {
        if (!BotConfig.isAdmin(adminId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
        String[] req = DatabaseManager.getMemberRequest(reqId);
        if (req == null) { bot.editNav(chatId, msgId, "❌ Запрос не найден.", Keyboards.backToHome()); return; }
        long   targetId  = Long.parseLong(req[1]);
        String username  = req[2];
        DatabaseManager.addMember(targetId, username, adminId);
        DatabaseManager.approveMemberRequest(reqId);
        // editNav — admin-сообщение, Markdown безопасен (нет пользовательского текста)
        bot.editNav(chatId, msgId, "✅ Принято\n\nПользователь " + username + " добавлен как участник.", Keyboards.backToHome());
        // sendText к пользователю — без Markdown, его username может содержать спецсимволы
        bot.sendText(targetId, "🎉 Ваш запрос принят!\n\nВы теперь участник группы " + BotConfig.BAND_NAME + ".\nНажмите /start.");
    }

    private void handleDenyRequest(long chatId, int msgId, long adminId, long reqId) {
        if (!BotConfig.isAdmin(adminId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
        String[] req = DatabaseManager.getMemberRequest(reqId);
        if (req == null) { bot.editNav(chatId, msgId, "❌ Запрос не найден.", Keyboards.backToHome()); return; }
        long   targetId  = Long.parseLong(req[1]);
        String username  = req[2];
        DatabaseManager.denyMemberRequest(reqId);
        bot.editNav(chatId, msgId, "❌ Отклонено\n\nЗапрос " + username + " отклонён.", Keyboards.backToHome());
        bot.sendText(targetId, "ℹ️ Ваш запрос отклонён. Если считаете ошибкой — напишите через /feedback.");
    }*/

    private void handleApproveRequest(long chatId, int msgId, long adminId, long reqId, String displayName) {
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
                        "(принято администратором " + displayName + ")");
        //displayName - имя админа

        // Уведомляем пользователя
        bot.sendText(targetId,
                "🎉 Ваш запрос принят!\n\nВы теперь участник группы " + BotConfig.BAND_NAME + ".\nНажмите /start.");
    }

    private void handleDenyRequest(long chatId, int msgId, long adminId, long reqId, String displayName) {
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
                        "(отклонено администратором " + displayName + ")");

        bot.sendText(targetId,
                "ℹ️ Ваш запрос отклонён. Если считаете ошибкой — напишите через /feedback.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ГОЛОСОВАНИЕ
    // ════════════════════════════════════════════════════════════════════════

    private void handleVote(String data, long chatId, int msgId, long userId) {
        String[] parts = data.split("_");
        if (parts.length != 3) return;
        long pollId   = parseLong(parts[1]);
        long optionId = parseLong(parts[2]);
        boolean recorded = DatabaseManager.votePoll(pollId, userId, optionId);
        List<String[]> results = DatabaseManager.getPollResults(pollId);
        int total = results.stream().mapToInt(r -> parseInt(r[2], 0)).sum();
        StringBuilder t = new StringBuilder(recorded ? "✅ *Голос принят!*\n\n" : "ℹ️ *Вы уже голосовали*\n\n");
        t.append("📊 *Результаты*\n\n");
        for (String[] r : results) {
            int v = parseInt(r[2], 0); int pct = total > 0 ? (v * 100 / total) : 0;
            t.append(NavigationHandler.esc(r[1])).append(": ").append(v).append(" гол. (").append(pct).append("%)\n");
        }
        bot.editNav(chatId, msgId, t.toString(), Keyboards.backToHome());
    }

    // ════════════════════════════════════════════════════════════════════════
    // УТИЛИТЫ
    // ════════════════════════════════════════════════════════════════════════

    private void ack(String cbId) {
        try { AnswerCallbackQuery a = new AnswerCallbackQuery(); a.setCallbackQueryId(cbId); bot.execute(a); }
        catch (Exception e) { log.error("ack", e); }
    }

    private static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; } }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
}
