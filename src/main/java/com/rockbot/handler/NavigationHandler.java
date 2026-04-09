package com.rockbot.handler;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db.DatabaseManager;
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
            case MEMBER   -> "\uD83E\uDE95 Участник группы";
            case LISTENER -> "🎧 Слушатель";
        };
        boolean isStaff    = role != Role.LISTENER;
        boolean isListener = role == Role.LISTENER;
        boolean hasAlbums  = DatabaseManager.hasAlbums();
        boolean isSub      = DatabaseManager.isSubscribed(userId);
        String text = "\uD83D\uDE08 Добро пожаловать в бот группы *" + esc(BotConfig.BAND_NAME) + "* \uD83D\uDE08" + "\n\nВаш статус: " + roleName + "\n\nВыберите раздел:";
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
        showSong(chatId, msgId, userId, songId, true);
    }

    public void showSong(long chatId, int msgId, long userId, long songId, boolean sendAudio) {
        String[] song = DatabaseManager.getSong(songId);
        if (song == null) { showHome(chatId, msgId, userId); return; }
        boolean hasLyrics  = DatabaseManager.getLyrics(songId) != null;
        boolean hasChords  = !DatabaseManager.getChordsList(songId).isEmpty();
        boolean hasInstr   = DatabaseManager.hasInstrumentals(songId);
        boolean hasHistory = !song[3].isBlank();
        boolean canEdit    = BotConfig.canEdit(userId);
        int     backPage   = UserSession.getLastSongsPage(userId);
        String navText = buildSongText(song);
        InlineKeyboardMarkup navKbd = Keyboards.songDetail(
                songId, hasLyrics, hasChords, hasInstr, hasHistory, canEdit, backPage);
        if (!song[4].isBlank() && sendAudio) {
            bot.editNav(chatId, navId(userId, msgId), navText, navKbd);
            bot.sendAudioMsg(chatId, userId, song[4], "🎵 " + song[1]);
            smartNav(chatId, userId, navId(userId, msgId), navText, navKbd);
        } else {
            smartNav(chatId, userId, msgId, navText, navKbd);
        }
    }

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
        List<String[]> instrumentals = DatabaseManager.getInstrumentals(songId);
        String[] song = DatabaseManager.getSong(songId);
        String title  = song != null ? song[1] : "#" + songId;
        
        if (instrumentals.isEmpty()) { 
            smartNav(chatId, userId, msgId, "🎼 Инструменталы не загружены.", Keyboards.backToSong(songId)); 
            return; 
        }
        
        // Если только один инструментал - сразу отправляем
        if (instrumentals.size() == 1) {
            String instrumentName = instrumentals.get(0)[0];
            String fileId = instrumentals.get(0)[1];
            bot.editNav(chatId, navId(userId, msgId), "🎼 *" + esc(title) + "* — " + esc(instrumentName) + "\n\n_Отправляется…_", Keyboards.backToSong(songId));
            bot.sendAudioMsg(chatId, userId, fileId, "🎼 " + title + " (" + instrumentName + ")");
            smartNav(chatId, userId, navId(userId, msgId), "🎼 *" + esc(title) + "* — " + esc(instrumentName) + "\n\n_Отправлено_ ✅", Keyboards.backToSong(songId));
            return;
        }
        
        // Если несколько - показываем меню выбора
        smartNav(chatId, userId, msgId, "🎼 *Инструменталы «" + esc(title) + "»*\n\nВыберите инструмент:", 
                Keyboards.instrumentalList(songId, instrumentals));
    }

    /** Отправляет конкретный инструментал */
    public void sendInstrumental(long chatId, int msgId, long userId, long songId, String instrumentName) {
        String fileId = DatabaseManager.getInstrumentalFileId(songId, instrumentName);
        String[] song = DatabaseManager.getSong(songId);
        String title = song != null ? song[1] : "#" + songId;
        
        if (fileId == null) {
            smartNav(chatId, userId, msgId, "❌ Инструментал не найден.", Keyboards.backToSong(songId));
            return;
        }
        
        bot.editNav(chatId, navId(userId, msgId), "🎼 *" + esc(title) + "* — " + esc(instrumentName) + "\n\n_Отправляется…_", Keyboards.backToSong(songId));
        bot.sendAudioMsg(chatId, userId, fileId, "🎼 " + title + " (" + instrumentName + ")");
        smartNav(chatId, userId, navId(userId, msgId), "🎼 *" + esc(title) + "* — " + esc(instrumentName) + "\n\n_Отправлено_ ✅", Keyboards.backToSong(songId));
    }

    /** Показывает меню выбора инструмента для добавления инструментала */
    public void showInstrumentalInput(long chatId, int msgId, long userId, long songId) {
        if (!BotConfig.canEdit(userId)) { 
            smartNav(chatId, userId, msgId, "🔒 Только для участников.", Keyboards.backToEditMenu(songId)); 
            return; 
        }
        smartNav(chatId, userId, msgId, 
                "🎼 *Добавить инструментал*\n\nВыберите тип или введите название инструмента:", 
                Keyboards.instrumentalTypeSelect(songId));
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

    /*public void showEvents(long chatId, int msgId, long userId) {
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
    }*/
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

    public void showRehearsals(long chatId, int msgId, long userId) {
        if (!BotConfig.canEdit(userId)) { smartNav(chatId, userId, msgId, "🔒 Только для участников.", Keyboards.backToHome()); return; }
        List<String[]> rh = DatabaseManager.getUpcomingRehearsals();
        boolean canAdd = true;
        if (rh.isEmpty()) {
            smartNav(chatId, userId, msgId, "🎸 *Репетиции*\n\nПредстоящих репетиций нет.", Keyboards.rehearsalList(canAdd)); return;
        }
        smartNav(chatId, userId, msgId, "🎸 *Репетиции*\n\nВыберите репетицию:", Keyboards.rehearsalListWithButtons(rh, canAdd));
    }
    // NavigationHandler.java
    public void showRehearsal(long chatId, int msgId, long userId, long rhId) {
        String[] r = DatabaseManager.getRehearsal(rhId);
        if (r == null) { showRehearsals(chatId, msgId, userId); return; }
        String t = "🎸 *Репетиция*\n\n🗓 *" + esc(r[1]) + "*\n" +
                (r[2].isBlank() ? "" : "\n" + esc(r[2]));
        smartNav(chatId, userId, msgId, t, Keyboards.rehearsalCard(rhId, BotConfig.canEdit(userId)));
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
                "👤 *Участник*\n\n" +
                "Имя: " + esc(username) + "\n" +
                "ID: `" + targetId + "`",
                Keyboards.memberCard(targetId));
    }

    /** Подтверждение удаления участника */
    public void showMemberDeleteConfirm(long chatId, int msgId, long userId, long targetId) {
        if (!BotConfig.isAdmin(userId)) { smartNav(chatId, userId, msgId, "🔒", Keyboards.backToHome()); return; }
        List<String[]> members = DatabaseManager.getAllMembers();
        String username = members.stream().filter(m -> m[0].equals(String.valueOf(targetId)))
                .map(m -> m[1]).findFirst().orElse("id:" + targetId);
        smartNav(chatId, userId, msgId,
                "🗑 *Удалить участника «" + esc(username) + "»?*\n\nID: `" + targetId + "`",
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
                "🔓 *Разблокировать «" + esc(username) + "»?*\n\nID: `" + targetId + "`",
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

    private static String fitText(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        int cut = text.lastIndexOf('\n', maxLen - 50);
        if (cut < maxLen / 2) cut = maxLen - 50;
        return text.substring(0, cut) + "\n\n_… текст обрезан_";
    }
}
