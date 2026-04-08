package com.rockbot.db2;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db2.DatabaseManager;
import com.rockbot.util.BotConfig;
import com.rockbot.util.Keyboards;
import com.rockbot.util.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.List;

/**
 * CallbackHandler — маршрутизирует нажатия inline-кнопок.
 *
 * При каждом нажатии кнопки:
 *  1. Формируем displayName из cbq.getFrom() и сохраняем в known_users
 *  2. Маршрутизируем callback
 *  3. Нигде не показываем числовые ID пользователям — только displayName
 */
public class CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);
    private final RockBandBot       bot;
    private final NavigationHandler nav;

    public CallbackHandler(RockBandBot bot) { this.bot = bot; this.nav = new NavigationHandler(bot); }

    public void handle(CallbackQuery cbq) {
        String data   = cbq.getData();
        long   chatId = cbq.getMessage().getChatId();
        int    msgId  = cbq.getMessage().getMessageId();
        User   from   = cbq.getFrom();
        long   userId = from.getId();

        // Обновляем known_users при каждом нажатии кнопки
        String displayName = MessageHandler.formatUser(from.getFirstName(), from.getLastName(), from.getUserName());
        DatabaseManager.upsertKnownUser(userId, displayName, from.getUserName());
        UserSession.set(userId, "displayName", displayName);

        ack(cbq.getId());
        log.debug("Callback userId={} data={}", userId, data);
        try { route(data, chatId, msgId, userId); }
        catch (Exception e) {
            log.error("Ошибка callback '{}': {}", data, e.getMessage(), e);
            bot.editNav(chatId, msgId, "❌ Произошла ошибка.", Keyboards.backToHome());
        }
    }

    private void route(String data, long chatId, int msgId, long userId) {

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

            // Подписка / отписка прямо из главного меню
            case "sub" -> {
                // Берём displayName из сессии (обновлён в handle())
                String dname = UserSession.get(userId, "displayName");
                DatabaseManager.subscribe(userId, dname.isBlank() ? "Пользователь" : dname);
                bot.editNav(chatId, msgId, "🔔 Вы подписались на уведомления!", Keyboards.backToHome());
                return;
            }
            case "unsub" -> {
                DatabaseManager.unsubscribe(userId);
                bot.editNav(chatId, msgId, "🔕 Вы отписались от уведомлений.", Keyboards.backToHome());
                return;
            }

            // Альбом: сохранить / отменить
            case "albs" -> { handleAlbumSave(chatId, msgId, userId);   return; }
            case "albc" -> { UserSession.clearState(userId); nav.showHome(chatId, msgId, userId); return; }
        }

        // ── Список песен ─────────────────────────────────────────────────
        if (data.startsWith("sl_")) {
            nav.showSongs(chatId, msgId, userId, parseInt(data.substring(3), 0)); return;
        }

        // ── Альбом (просмотр): al_<albumId> ─────────────────────────────
        if (data.startsWith("al_")) {
            nav.showAlbumSongs(chatId, msgId, userId, parseLong(data.substring(3))); return;
        }

        // ── Карточка песни: s_<id> ────────────────────────────────────────
        if (data.startsWith("s_") && data.length() > 2) {
            long songId = parseLong(data.substring(2));
            if (songId > 0) { nav.showSong(chatId, msgId, userId, songId); return; }
        }

        // ── Текст, аккорды, инструментал, история ────────────────────────
        if (data.startsWith("lyr_")) { nav.showLyrics(chatId, msgId, userId, parseLong(data.substring(4)));      return; }
        if (data.startsWith("cho_")) { nav.showChordsMenu(chatId, msgId, userId, parseLong(data.substring(4)));  return; }
        if (data.startsWith("ch_")) {
            String[] p = data.split("_", 3);
            if (p.length == 3) nav.showChords(chatId, msgId, userId, parseLong(p[1]), parseLong(p[2]));
            return;
        }
        if (data.startsWith("ins_")) { nav.showInstrumental(chatId, msgId, userId, parseLong(data.substring(4))); return; }
        if (data.startsWith("his_")) { nav.showHistory(chatId, msgId, userId, parseLong(data.substring(4)));      return; }

        // ── Редактирование ────────────────────────────────────────────────
        if (data.startsWith("edt_")) { nav.showEditMenu(chatId, msgId, userId, parseLong(data.substring(4)));    return; }
        if (data.startsWith("et_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_TITLE,   "Введите новое название:"); return; }
        if (data.startsWith("ea_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_ALBUM,   "Введите альбом (или «нет»):"); return; }
        if (data.startsWith("eau_")) { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(4)), UserSession.State.AWAIT_EDIT_AUDIO,   "Отправьте новый аудиофайл:"); return; }
        if (data.startsWith("el_")) {
            long id = parseLong(data.substring(3));
            String cur = DatabaseManager.getLyrics(id);
            String hint = cur != null ? "Текущий текст (начало):\n" + cur.substring(0, Math.min(150, cur.length())) + "…\n\n" : "";
            nav.showEditInput(chatId, msgId, userId, id, UserSession.State.AWAIT_EDIT_LYRICS, hint + "Вставьте новый текст:");
            return;
        }
        if (data.startsWith("ec_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_CHORDS_INSTR, "Для какого инструмента? (гитара / бас / пианино / …)"); return; }
        if (data.startsWith("ei_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_INSTRUMENTAL, "Отправьте аудиофайл инструментала:"); return; }
        if (data.startsWith("eh_"))  { nav.showEditInput(chatId, msgId, userId, parseLong(data.substring(3)), UserSession.State.AWAIT_EDIT_HISTORY,       "Напишите историю создания:"); return; }

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

        // ── Запросы: одобрить / отклонить ────────────────────────────────
        if (data.startsWith("mrok_")) { handleApproveRequest(chatId, msgId, userId, parseLong(data.substring(5))); return; }
        if (data.startsWith("mrno_")) { handleDenyRequest(chatId, msgId, userId, parseLong(data.substring(5)));    return; }

        // ── Участники: карточка, подтверждение удаления, удаление ─────────
        if (data.startsWith("mm_"))  { nav.showMemberCard(chatId, msgId, userId, parseLong(data.substring(3)));          return; }
        if (data.startsWith("mmd_")) { nav.showMemberDeleteConfirm(chatId, msgId, userId, parseLong(data.substring(4))); return; }
        if (data.startsWith("mmdo_")) {
            long targetId = parseLong(data.substring(5));
            if (!BotConfig.isAdmin(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            // Берём displayName перед удалением, чтобы показать его в сообщении
            String targetName = DatabaseManager.getDisplayName(targetId);
            DatabaseManager.removeMember(targetId);
            // Показываем имя удалённого участника, не его ID
            bot.editNav(chatId, msgId, "✅ Участник " + targetName + " удалён из группы.", Keyboards.backToHome());
            return;
        }

        // ── Разблокировка ─────────────────────────────────────────────────
        if (data.startsWith("ublk_") && !data.startsWith("ublko_")) {
            nav.showUnblockConfirm(chatId, msgId, userId, parseLong(data.substring(5))); return;
        }
        if (data.startsWith("ublko_")) {
            long targetId = parseLong(data.substring(6));
            if (!BotConfig.isAdmin(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            String targetName = DatabaseManager.getDisplayName(targetId);
            DatabaseManager.unblockUser(targetId);
            // Показываем имя разблокированного, не ID
            bot.editNav(chatId, msgId, "✅ " + targetName + " разблокирован.", Keyboards.backToHome());
            return;
        }

        // ── Выбор песни для альбома: ats_<songId> ─────────────────────────
        if (data.startsWith("ats_")) {
            if (!BotConfig.canEdit(userId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }
            long songId = parseLong(data.substring(4));
            // Переключаем выбор: если выбрана — убираем (с пересчётом номеров), если нет — добавляем
            UserSession.toggleSelectedSong(userId, songId);
            // Перерисовываем экран выбора с обновлёнными кнопками
            nav.showAlbumSongPicker(chatId, msgId, userId);
            return;
        }

        // ── Wizards: запуск добавления контента из кнопок ─────────────────
        if (data.startsWith("wiz_")) { handleWizard(data.substring(4), chatId, msgId, userId); return; }

        // ── Голосование ───────────────────────────────────────────────────
        if (data.startsWith("vote_")) { handleVote(data, chatId, msgId, userId); return; }

        log.warn("Неизвестный callback: {}", data);
    }

    // ════════════════════════════════════════════════════════════════════════
    // WIZARD: запуск добавления из кнопок «➕ Добавить ...»
    // ════════════════════════════════════════════════════════════════════════

    private void handleWizard(String type, long chatId, int msgId, long userId) {
        if (!BotConfig.canEdit(userId)) {
            bot.editNav(chatId, msgId, "🔒 Только для участников группы.", Keyboards.backToHome()); return;
        }
        switch (type) {
            case "song" -> {
                UserSession.setState(userId, UserSession.State.AWAIT_SONG_TITLE);
                bot.editNav(chatId, msgId, "🎵 Добавление песни\n\nНазвание:", Keyboards.inputCancel("h"));
            }
            case "album" -> {
                UserSession.setState(userId, UserSession.State.AWAIT_ALBUM_NAME);
                bot.editNav(chatId, msgId, "💿 Добавление альбома\n\nНазвание альбома:", Keyboards.inputCancel("h"));
            }
            case "event" -> {
                UserSession.setState(userId, UserSession.State.AWAIT_EVENT_DATE);
                bot.editNav(chatId, msgId, "📅 Добавление концерта\n\n🗓 Дата и время (любой формат):", Keyboards.inputCancel("ev"));
            }
            case "rehearsal" -> {
                UserSession.setState(userId, UserSession.State.AWAIT_REHEARSAL_DATE);
                bot.editNav(chatId, msgId, "🎸 Добавление репетиции\n\n🗓 Дата и время (любой формат):", Keyboards.inputCancel("rh"));
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

        long albumId = DatabaseManager.createAlbum(albumName, selected, userId);
        UserSession.clearState(userId);

        if (albumId > 0) {
            bot.editNav(chatId, msgId,
                    "✅ Альбом " + NavigationHandler.esc(albumName) + " создан!\nПесен: " + selected.size(),
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

    /**
     * Администратор одобряет запрос.
     * Показывает displayName пользователя, а не его ID.
     */
    private void handleApproveRequest(long chatId, int msgId, long adminId, long reqId) {
        if (!BotConfig.isAdmin(adminId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }

        String[] req = DatabaseManager.getMemberRequest(reqId);
        if (req == null) { bot.editNav(chatId, msgId, "❌ Запрос не найден.", Keyboards.backToHome()); return; }

        // req = [id, user_id, display_name, message, status]
        long   targetId    = Long.parseLong(req[1]);
        String displayName = req[2]; // имя пользователя, а не ID

        DatabaseManager.addMember(targetId, displayName, adminId);
        DatabaseManager.approveMemberRequest(reqId);

        // Получаем имя самого администратора для отображения в сообщении
        String adminName = DatabaseManager.getDisplayName(adminId);

        // Редактируем сообщение с кнопками — убираем кнопки, меняем текст
        // Пользователь видит имена людей, а не числовые ID
        bot.editNav(chatId, msgId,
                "✅ Принято\n\nПользователь " + displayName + " добавлен как участник группы.\n" +
                "Решение принял: " + adminName,
                Keyboards.backToHome());

        // Уведомляем пользователя о принятии — без ID, только имя группы
        bot.sendText(targetId,
                "🎉 Ваш запрос принят!\n\nВы теперь участник группы " + BotConfig.BAND_NAME + ".\nНажмите /start.");
    }

    /**
     * Администратор отклоняет запрос.
     */
    private void handleDenyRequest(long chatId, int msgId, long adminId, long reqId) {
        if (!BotConfig.isAdmin(adminId)) { bot.editNav(chatId, msgId, "🔒", Keyboards.backToHome()); return; }

        String[] req = DatabaseManager.getMemberRequest(reqId);
        if (req == null) { bot.editNav(chatId, msgId, "❌ Запрос не найден.", Keyboards.backToHome()); return; }

        long   targetId    = Long.parseLong(req[1]);
        String displayName = req[2];

        DatabaseManager.denyMemberRequest(reqId);

        String adminName = DatabaseManager.getDisplayName(adminId);

        bot.editNav(chatId, msgId,
                "❌ Отклонено\n\nЗапрос " + displayName + " отклонён.\n" +
                "Решение принял: " + adminName,
                Keyboards.backToHome());

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

        StringBuilder t = new StringBuilder(recorded ? "✅ Голос принят!\n\n" : "ℹ️ Вы уже голосовали\n\n");
        t.append("📊 Результаты\n\n");
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
