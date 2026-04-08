package com.rockbot.db2;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db2.DatabaseManager;
import com.rockbot.util.BotConfig;
import com.rockbot.util.Keyboards;
import com.rockbot.util.Role;
import com.rockbot.util.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

/**
 * NavigationHandler — все «экраны» бота.
 * smartNav() решает: editNav (на месте) или resendNav (переслать вниз).
 */
public class NavigationHandler {

    private static final Logger log = LoggerFactory.getLogger(NavigationHandler.class);
    private static final int MAX_LEN = 3800;
    private final RockBandBot bot;

    public NavigationHandler(RockBandBot bot) { this.bot = bot; }

    // ════════════════════════════════════════════════════════════════════════
    // УМНАЯ НАВИГАЦИЯ
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Если navStale=true или msgsSinceNav >= 2 → resendNav (пересылаем nav вниз).
     * Иначе → editNav (редактируем на месте).
     */
    private void smartNav(long chatId, long userId, int msgId, String text, InlineKeyboardMarkup kbd) {
        if (UserSession.isNavStale(userId) || UserSession.getMsgsSinceNav(userId) >= 2) {
            bot.resendNav(chatId, userId, text, kbd);
        } else {
            bot.editNav(chatId, msgId, text, kbd);
        }
    }

    private int navId(long userId, int fallback) {
        int id = UserSession.getNavMessageId(userId);
        return id > 0 ? id : fallback;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ГЛАВНОЕ МЕНЮ
    // ════════════════════════════════════════════════════════════════════════

    public void showHome(long chatId, int msgId, long userId) {
        Role role = BotConfig.getRole(userId);
        String roleName = switch (role) {
            case ADMIN    -> "👑 Администратор";
            case MEMBER   -> "🎸 Участник группы";
            case LISTENER -> "🎧 Слушатель";
        };
        boolean isStaff    = role != Role.LISTENER;
        boolean isListener = role == Role.LISTENER;
        boolean hasAlbums  = DatabaseManager.hasAlbums();
        boolean isSub      = DatabaseManager.isSubscribed(userId);
        String text = "🎸 *" + esc(BotConfig.BAND_NAME) + "*\n\nВаш статус: " + roleName + "\n\nВыберите раздел:";
        smartNav(chatId, userId, msgId, text, Keyboards.home(isStaff, isListener, hasAlbums, isSub));
    }

    // ════════════════════════════════════════════════════════════════════════
    // СПИСОК ПЕСЕН
    // ════════════════════════════════════════════════════════════════════════

    public void showSongs(long chatId, int msgId, long userId, int page) {
        UserSession.setLastSongsPage(userId, page);
        List<String[]> all = DatabaseManager.getAllSongs();
        if (all.isEmpty()) {
            String text = "🎵 *Все песни*\n\nПесен пока нет.";
            smartNav(chatId, userId, msgId, text, Keyboards.songList(List.of(), 0, 1, BotConfig.canEdit(userId)));
            return;
        }
        int totalPages = (int) Math.ceil((double) all.size() / Keyboards.PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * Keyboards.PAGE_SIZE;
        int to   = Math.min(from + Keyboards.PAGE_SIZE, all.size());
        StringBuilder t = new StringBuilder("🎵 *Все песни " + esc(BotConfig.BAND_NAME) + "*");
        if (totalPages > 1) t.append("\nСтраница ").append(page + 1).append(" из ").append(totalPages);
        t.append("\n\n🔇 = аудио не загружено");
        smartNav(chatId, userId, msgId, t.toString(),
                Keyboards.songList(all.subList(from, to), page, totalPages, BotConfig.canEdit(userId)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // АЛЬБОМЫ
    // ════════════════════════════════════════════════════════════════════════

    public void showAlbums(long chatId, int msgId, long userId) {
        List<String[]> albums = DatabaseManager.getAllAlbums();
        if (albums.isEmpty()) {
            // Альбомов нет — только для участников/админов показываем кнопку
            if (BotConfig.canEdit(userId)) {
                smartNav(chatId, userId, msgId,
                        "💿 *Альбомы*\n\nАльбомов пока нет. Создайте первый!",
                        Keyboards.albumList(List.of(), true));
            } else {
                smartNav(chatId, userId, msgId, "💿 Альбомов пока нет.", Keyboards.backToHome());
            }
            return;
        }
        smartNav(chatId, userId, msgId,
                "💿 *Альбомы " + esc(BotConfig.BAND_NAME) + "*\n\nВыберите альбом:",
                Keyboards.albumList(albums, BotConfig.canEdit(userId)));
    }

    /** Показывает песни альбома */
    public void showAlbumSongs(long chatId, int msgId, long userId, long albumId) {
        String name = DatabaseManager.getAlbumName(albumId);
        if (name == null) { showAlbums(chatId, msgId, userId); return; }
        List<String[]> songs = DatabaseManager.getAlbumSongs(albumId);
        UserSession.setLastSongsPage(userId, 0);
        if (songs.isEmpty()) {
            smartNav(chatId, userId, msgId, "💿 *" + esc(name) + "*\n\nПесен в альбоме нет.",
                    Keyboards.backToHome());
            return;
        }
        smartNav(chatId, userId, msgId,
                "💿 *" + esc(name) + "* — " + songs.size() + " шт.",
                Keyboards.albumSongList(songs));
    }

    /**
     * Экран выбора песен при создании альбома.
     * Перестраивает кнопки после каждого переключения.
     */
    public void showAlbumSongPicker(long chatId, int msgId, long userId) {
        List<String[]> all = DatabaseManager.getAllSongs();
        List<Long> selected = UserSession.getSelectedSongs(userId);
        String albumName = UserSession.get(userId, "albumName");

        String text = "💿 *Создание альбома «" + esc(albumName) + "»*\n\n" +
                      "Выбрано: " + selected.size() + " песен\n" +
                      "Нажмите на песню чтобы добавить/убрать её. Порядок нажатий = порядок в альбоме.";

        if (all.isEmpty()) {
            smartNav(chatId, userId, msgId, "❌ Песен нет. Сначала добавьте песни через /addsong.",
                    Keyboards.inputCancel("h"));
            return;
        }
        smartNav(chatId, userId, msgId, text, Keyboards.albumSongPicker(all, userId));
    }

    // ════════════════════════════════════════════════════════════════════════
    // КАРТОЧКА ПЕСНИ
    // ════════════════════════════════════════════════════════════════════════

    public void showSong(long chatId, int msgId, long userId, long songId) {
        String[] song = DatabaseManager.getSong(songId);
        if (song == null) { showHome(chatId, msgId, userId); return; }
        boolean hasLyrics  = DatabaseManager.getLyrics(songId) != null;
        boolean hasChords  = !DatabaseManager.getChordsList(songId).isEmpty();
        boolean hasInstr   = DatabaseManager.getInstrumentalFileId(songId) != null;
        boolean hasHistory = !song[3].isBlank();
        boolean canEdit    = BotConfig.canEdit(userId);
        int     backPage   = UserSession.getLastSongsPage(userId);
        String navText = buildSongText(song);
        InlineKeyboardMarkup navKbd = Keyboards.songDetail(
                songId, hasLyrics, hasChords, hasInstr, hasHistory, canEdit, backPage);
        if (!song[4].isBlank()) {
            bot.editNav(chatId, navId(userId, msgId), navText, navKbd);
            bot.sendAudioMsg(chatId, userId, song[4], "🎵 " + song[1]);
            smartNav(chatId, userId, navId(userId, msgId), navText, navKbd);
        } else {
            smartNav(chatId, userId, msgId, navText, navKbd);
        }
    }

    private String buildSongText(String[] song) {
        StringBuilder t = new StringBuilder("🎸 *").append(esc(song[1])).append("*");
        if (!song[2].isBlank()) t.append("\n💿 ").append(esc(song[2]));
        t.append(!song[4].isBlank() ? "\n\n_🎵 Трек отправлен ниже_ ⬇️" : "\n\n_Аудиофайл не загружен_");
        return t.toString();
    }

    // ════════════════════════════════════════════════════════════════════════
    // ТЕКСТ, АККОРДЫ, ИНСТРУМЕНТАЛ, ИСТОРИЯ
    // ════════════════════════════════════════════════════════════════════════

    public void showLyrics(long chatId, int msgId, long userId, long songId) {
        String[] song = DatabaseManager.getSong(songId);
        String lyrics = DatabaseManager.getLyrics(songId);
        if (song == null || lyrics == null) {
            smartNav(chatId, userId, msgId, "❌ Текст не найден.", Keyboards.backToSong(songId)); return;
        }
        String h = "📝 *" + esc(song[1]) + " — текст*\n\n";
        smartNav(chatId, userId, msgId, h + fitText(lyrics, MAX_LEN - h.length()), Keyboards.backToSong(songId));
    }

    public void showChordsMenu(long chatId, int msgId, long userId, long songId) {
        List<String[]> chords = DatabaseManager.getChordsList(songId);
        if (chords.isEmpty()) { smartNav(chatId, userId, msgId, "🎸 Аккорды не добавлены.", Keyboards.backToSong(songId)); return; }
        if (chords.size() == 1) { showChords(chatId, msgId, userId, songId, Long.parseLong(chords.get(0)[0])); return; }
        String[] song = DatabaseManager.getSong(songId);
        smartNav(chatId, userId, msgId, "🎸 *" + esc(song != null ? song[1] : "#" + songId) + " — аккорды*\n\nВыберите инструмент:",
                Keyboards.chordsInstruments(songId, chords));
    }

    public void showChords(long chatId, int msgId, long userId, long songId, long chordId) {
        String content = DatabaseManager.getChordById(chordId);
        String inst    = DatabaseManager.getChordInstrument(chordId);
        String[] song  = DatabaseManager.getSong(songId);
        if (content == null) { smartNav(chatId, userId, msgId, "❌ Аккорды не найдены.", Keyboards.backToSong(songId)); return; }
        String h = "🎸 *" + esc(song != null ? song[1] : "#" + songId) + " — " + esc(inst) + "*\n\n```\n";
        smartNav(chatId, userId, msgId, h + fitText(content, MAX_LEN - h.length() - 4) + "\n```", Keyboards.backToSong(songId));
    }

    public void showInstrumental(long chatId, int msgId, long userId, long songId) {
        String fileId = DatabaseManager.getInstrumentalFileId(songId);
        String[] song = DatabaseManager.getSong(songId);
        String title  = song != null ? song[1] : "#" + songId;
        if (fileId == null) { smartNav(chatId, userId, msgId, "🎼 Инструментал не загружен.", Keyboards.backToSong(songId)); return; }
        bot.editNav(chatId, navId(userId, msgId), "🎼 *" + esc(title) + "* — инструментал\n\n_Отправляется…_", Keyboards.backToSong(songId));
        bot.sendAudioMsg(chatId, userId, fileId, "🎼 " + title + " (инструментал)");
        smartNav(chatId, userId, navId(userId, msgId), "🎼 *" + esc(title) + "* — инструментал\n\n_Отправлено_ ✅", Keyboards.backToSong(songId));
    }

    public void showHistory(long chatId, int msgId, long userId, long songId) {
        String[] song = DatabaseManager.getSong(songId);
        if (song == null) { smartNav(chatId, userId, msgId, "❌ Песня не найдена.", Keyboards.backToHome()); return; }
        if (song[3].isBlank()) { smartNav(chatId, userId, msgId, "📖 История не написана.", Keyboards.backToSong(songId)); return; }
        String h = "📖 *История «" + esc(song[1]) + "»*\n\n";
        smartNav(chatId, userId, msgId, h + fitText(song[3], MAX_LEN - h.length()), Keyboards.backToSong(songId));
    }

    // ════════════════════════════════════════════════════════════════════════
    // КОНЦЕРТЫ, РЕПЕТИЦИИ
    // ════════════════════════════════════════════════════════════════════════

    public void showEvents(long chatId, int msgId, long userId) {
        List<String[]> events = DatabaseManager.getUpcomingEvents();
        boolean canAdd = BotConfig.canEdit(userId);
        if (events.isEmpty()) {
            smartNav(chatId, userId, msgId, "📅 *Концерты*\n\nКонцертов пока нет.", Keyboards.eventList(canAdd)); return;
        }
        StringBuilder t = new StringBuilder("📅 *Концерты*\n\n");
        for (String[] e : events) {
            t.append("🎸 *").append(esc(e[2])).append("*\n🗓 ").append(esc(e[1])).append("\n");
            if (!e[3].isBlank()) t.append("📋 ").append(esc(e[3])).append("\n");
            t.append("\n");
        }
        smartNav(chatId, userId, msgId, fitText(t.toString(), MAX_LEN), Keyboards.eventList(canAdd));
    }

    public void showRehearsals(long chatId, int msgId, long userId) {
        if (!BotConfig.canEdit(userId)) { smartNav(chatId, userId, msgId, "🔒 Только для участников.", Keyboards.backToHome()); return; }
        List<String[]> rh = DatabaseManager.getUpcomingRehearsals();
        boolean canAdd = true;
        if (rh.isEmpty()) {
            smartNav(chatId, userId, msgId, "🎸 *Репетиции*\n\nПредстоящих репетиций нет.", Keyboards.rehearsalList(canAdd)); return;
        }
        StringBuilder t = new StringBuilder("🎸 *Репетиции*\n\n");
        for (String[] r : rh) {
            t.append("🔸 🗓 *").append(esc(r[1])).append("*\n");
            if (!r[2].isBlank()) t.append(esc(r[2])).append("\n");
            t.append("\n");
        }
        smartNav(chatId, userId, msgId, fitText(t.toString(), MAX_LEN), Keyboards.rehearsalList(canAdd));
    }

    // ════════════════════════════════════════════════════════════════════════
    // ПОИСК
    // ════════════════════════════════════════════════════════════════════════

    public void showSearchInput(long chatId, int msgId, long userId) {
        UserSession.setState(userId, UserSession.State.AWAIT_SEARCH);
        smartNav(chatId, userId, msgId, "🔍 *Поиск*\n\nВведите название:", Keyboards.inputCancel("h"));
    }

    public void showSearchResults(long chatId, int msgId, long userId, String query) {
        UserSession.clearState(userId);
        List<String[]> results = DatabaseManager.searchSongs(query);
        if (results.isEmpty()) {
            smartNav(chatId, userId, msgId, "🔍 Ничего не найдено по запросу «" + esc(query) + "».", Keyboards.backToHome()); return;
        }
        UserSession.setLastSongsPage(userId, 0);
        smartNav(chatId, userId, msgId, "🔍 Результаты «" + esc(query) + "»: " + results.size() + " шт.",
                Keyboards.songList(results, 0, 1, false));
    }

    // ════════════════════════════════════════════════════════════════════════
    // РЕДАКТИРОВАНИЕ
    // ════════════════════════════════════════════════════════════════════════

    public void showEditMenu(long chatId, int msgId, long userId, long songId) {
        if (!BotConfig.canEdit(userId)) { smartNav(chatId, userId, msgId, "🔒 Только для участников.", Keyboards.backToSong(songId)); return; }
        String[] song = DatabaseManager.getSong(songId);
        String title  = song != null ? song[1] : "#" + songId;
        smartNav(chatId, userId, msgId, "✏️ *Редактирование: " + esc(title) + "*\n\nЧто изменить?", Keyboards.editMenu(songId));
    }

    public void showEditInput(long chatId, int msgId, long userId, long songId,
                              UserSession.State state, String prompt) {
        if (!BotConfig.canEdit(userId)) { smartNav(chatId, userId, msgId, "🔒 Только для участников.", Keyboards.backToSong(songId)); return; }
        UserSession.set(userId, "songId", String.valueOf(songId));
        UserSession.setState(userId, state);
        smartNav(chatId, userId, msgId, "✏️ " + prompt + "\n\n_Для отмены — кнопка ниже_", Keyboards.inputCancel("edt_" + songId));
    }

    public void showDeleteConfirm(long chatId, int msgId, long userId, long songId) {
        if (!BotConfig.canEdit(userId)) { smartNav(chatId, userId, msgId, "🔒 Только для участников.", Keyboards.backToSong(songId)); return; }
        String[] song = DatabaseManager.getSong(songId);
        String title  = song != null ? song[1] : "#" + songId;
        smartNav(chatId, userId, msgId, "🗑 *Удалить «" + esc(title) + "»?*\n\nВместе с песней удалятся текст, аккорды и инструментал.",
                Keyboards.deleteConfirm(songId));
    }

    // ════════════════════════════════════════════════════════════════════════
    // УЧАСТНИКИ И БЛОКИРОВКИ
    // ════════════════════════════════════════════════════════════════════════

    /** Список участников как кнопки */
    public void showMemberList(long chatId, int msgId, long userId) {
        if (!BotConfig.isAdmin(userId)) { smartNav(chatId, userId, msgId, "🔒 Только для администраторов.", Keyboards.backToHome()); return; }
        List<String[]> members = DatabaseManager.getAllMembers();
        if (members.isEmpty()) {
            smartNav(chatId, userId, msgId, "👥 *Участники*\n\nУчастников пока нет.", Keyboards.backToHome()); return;
        }
        smartNav(chatId, userId, msgId, "👥 *Участники группы*\nВыберите для управления:", Keyboards.memberList(members));
    }

    /** Карточка участника с кнопкой удаления */
    public void showMemberCard(long chatId, int msgId, long userId, long targetId) {
        if (!BotConfig.isAdmin(userId)) { smartNav(chatId, userId, msgId, "🔒", Keyboards.backToHome()); return; }
        List<String[]> members = DatabaseManager.getAllMembers();
        String username = members.stream().filter(m -> m[0].equals(String.valueOf(targetId)))
                .map(m -> m[1]).findFirst().orElse("id:" + targetId);
        smartNav(chatId, userId, msgId,
                "👤 *Участник*\n\n" + esc(username),
                Keyboards.memberCard(targetId));
    }

    /** Подтверждение удаления участника */
    public void showMemberDeleteConfirm(long chatId, int msgId, long userId, long targetId) {
        if (!BotConfig.isAdmin(userId)) { smartNav(chatId, userId, msgId, "🔒", Keyboards.backToHome()); return; }
        List<String[]> members = DatabaseManager.getAllMembers();
        String username = members.stream().filter(m -> m[0].equals(String.valueOf(targetId)))
                .map(m -> m[1]).findFirst().orElse("id:" + targetId);
        smartNav(chatId, userId, msgId,
                "🗑 *Удалить участника «" + esc(username) + "»?*",
                Keyboards.memberDeleteConfirm(targetId));
    }

    /** Список заблокированных пользователей */
    public void showBlockedList(long chatId, int msgId, long userId) {
        if (!BotConfig.isAdmin(userId)) { smartNav(chatId, userId, msgId, "🔒", Keyboards.backToHome()); return; }
        List<String[]> blocked = DatabaseManager.getBlockedUsers();
        if (blocked.isEmpty()) {
            smartNav(chatId, userId, msgId, "✅ Заблокированных пользователей нет.", Keyboards.backToHome()); return;
        }
        smartNav(chatId, userId, msgId, "🔒 *Заблокированные пользователи*\nВыберите для разблокировки:", Keyboards.blockedList(blocked));
    }

    /** Подтверждение разблокировки */
    public void showUnblockConfirm(long chatId, int msgId, long userId, long targetId) {
        if (!BotConfig.isAdmin(userId)) { smartNav(chatId, userId, msgId, "🔒", Keyboards.backToHome()); return; }
        List<String[]> blocked = DatabaseManager.getBlockedUsers();
        String username = blocked.stream().filter(b -> b[0].equals(String.valueOf(targetId)))
                .map(b -> b[1]).findFirst().orElse("id:" + targetId);
        smartNav(chatId, userId, msgId,
                "🔓 *Разблокировать «" + esc(username) + "»?*",
                Keyboards.unblockConfirm(targetId));
    }

    // ════════════════════════════════════════════════════════════════════════
    // ЗАПРОС СТАТУСА УЧАСТНИКА
    // ════════════════════════════════════════════════════════════════════════

    public void showMemberRequestInput(long chatId, int msgId, long userId) {
        if (BotConfig.canEdit(userId)) {
            smartNav(chatId, userId, msgId, "ℹ️ Вы уже являетесь участником или администратором.", Keyboards.backToHome()); return;
        }
        UserSession.setState(userId, UserSession.State.AWAIT_MEMBER_REQUEST_MSG);
        smartNav(chatId, userId, msgId,
                "📨 *Запрос на статус участника*\n\nНапишите о себе — кто вы и зачем нужен доступ.\n\nМожно написать «пропустить».",
                Keyboards.inputCancel("h"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // УТИЛИТЫ
    // ════════════════════════════════════════════════════════════════════════

    public static String esc(String text) {
        if (text == null) return "";
        return text.replace("\\","\\\\").replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("[","\\[");
    }

    private static String fitText(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        int cut = text.lastIndexOf('\n', maxLen - 50);
        if (cut < maxLen / 2) cut = maxLen - 50;
        return text.substring(0, cut) + "\n\n_… текст обрезан_";
    }
}
