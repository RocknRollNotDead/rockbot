package com.rockbot.util;

import java.util.*;

/**
 * Хранит состояние каждого пользователя в оперативной памяти.
 *
 * navMessageId    — ID nav-сообщения.
 * navStale        — true когда nav «уехал вверх»; при следующем showXxx → resendNav.
 * msgsSinceNav    — количество сообщений после nav; >= 2 → тоже resendNav.
 * state           — текущий шаг диалога.
 * scratch         — временные данные диалога.
 * lastSongsPage   — последняя страница списка песен.
 * selectedSongs   — упорядоченный список ID песен при создании альбома.
 */
public class UserSession {

    public enum State {
        NONE,

        // Добавление песни
        AWAIT_SONG_TITLE, AWAIT_SONG_ALBUM, AWAIT_SONG_AUDIO,

        // Редактирование песни
        AWAIT_EDIT_TITLE, AWAIT_EDIT_ALBUM, AWAIT_EDIT_AUDIO,
        AWAIT_EDIT_LYRICS, AWAIT_EDIT_CHORDS_INSTR, AWAIT_EDIT_CHORDS_TEXT,
        AWAIT_EDIT_INSTRUMENTAL, AWAIT_EDIT_INSTRUMENTAL_NAME, AWAIT_EDIT_HISTORY,

        // Поиск
        AWAIT_SEARCH,

        // Обратная связь (только слушатели)
        AWAIT_FEEDBACK,

        // Запрос статуса участника
        AWAIT_MEMBER_REQUEST_MSG,

        // Создание альбома
        AWAIT_ALBUM_NAME,
        AWAIT_ALBUM_SONGS,   // интерактивный выбор песен кнопками

        // Концерт (дата → место → описание)
        AWAIT_EVENT_DATE, AWAIT_EVENT_LOCATION, AWAIT_EVENT_DESC,

        // Репетиция (дата → описание)
        AWAIT_REHEARSAL_DATE, AWAIT_REHEARSAL_DESC,

        // Новости
        AWAIT_NEWS_TITLE, AWAIT_NEWS_BODY,

        // Опрос
        AWAIT_POLL_QUESTION, AWAIT_POLL_OPTIONS,

        // Смена получателя запросов
        AWAIT_SET_RECIPIENT_ID,

        // Информация о группе
        AWAIT_SET_BAND_INFO, AWAIT_ADD_BAND_INFO,
    }

    private static final Map<Long, Integer>          navIds       = new HashMap<>();
    private static final Map<Long, Integer>          msgsCount    = new HashMap<>();
    private static final Map<Long, Boolean>          staleFlags   = new HashMap<>();
    private static final Map<Long, State>            states       = new HashMap<>();
    private static final Map<Long, Map<String,String>> scratch    = new HashMap<>();
    private static final Map<Long, Integer>          songsPage    = new HashMap<>();
    private static final Map<Long, List<Long>>       selectedSongs = new HashMap<>();

    // ── Nav ───────────────────────────────────────────────────────────────
    public static int  getNavMessageId(long u)         { return navIds.getOrDefault(u, 0); }
    public static void setNavMessageId(long u, int id) { navIds.put(u, id); }

    // ── Счётчик и флаг «nav устарел» ──────────────────────────────────────
    public static int  getMsgsSinceNav(long u)   { return msgsCount.getOrDefault(u, 0); }
    public static void incMsgsSinceNav(long u)   { msgsCount.merge(u, 1, Integer::sum); }
    public static void resetMsgsSinceNav(long u) { msgsCount.put(u, 0); }

    public static boolean isNavStale(long u)    { return staleFlags.getOrDefault(u, false); }
    public static void    markNavStale(long u)  { staleFlags.put(u, true); }
    public static void    clearNavStale(long u) { staleFlags.put(u, false); }

    // ── State ─────────────────────────────────────────────────────────────
    public static State getState(long u)              { return states.getOrDefault(u, State.NONE); }
    public static void  setState(long u, State state) { states.put(u, state); }

    // ── Scratch (временные данные диалога) ────────────────────────────────
    public static void set(long u, String key, String val) {
        scratch.computeIfAbsent(u, k -> new HashMap<>()).put(key, val);
    }
    public static String get(long u, String key) {
        Map<String,String> m = scratch.get(u);
        return m != null ? m.getOrDefault(key, "") : "";
    }
    public static void clearState(long u) {
        states.remove(u);
        Map<String,String> m = scratch.get(u);
        if (m != null) m.clear();
        selectedSongs.remove(u);
    }

    // ── Страница списка песен ─────────────────────────────────────────────
    public static int  getLastSongsPage(long u)          { return songsPage.getOrDefault(u, 0); }
    public static void setLastSongsPage(long u, int page){ songsPage.put(u, page); }

    // ── Выбранные песни для альбома ───────────────────────────────────────

    /** Возвращает упорядоченный список ID выбранных песен */
    public static List<Long> getSelectedSongs(long u) {
        return selectedSongs.getOrDefault(u, new ArrayList<>());
    }

    /**
     * Переключает выбор песни:
     *   - если песня уже выбрана → убирает её (номера остальных сдвигаются)
     *   - если не выбрана → добавляет в конец
     */
    public static void toggleSelectedSong(long u, long songId) {
        List<Long> list = selectedSongs.computeIfAbsent(u, k -> new ArrayList<>());
        if (list.contains(songId)) {
            list.remove(songId);  // убираем, остальные автоматически сдвигаются
        } else {
            list.add(songId);     // добавляем в конец (позиция = size)
        }
    }

    /** Возвращает позицию песни в альбоме (1-based), 0 если не выбрана */
    public static int getSongPosition(long u, long songId) {
        List<Long> list = selectedSongs.getOrDefault(u, List.of());
        int idx = list.indexOf(songId);
        return idx >= 0 ? idx + 1 : 0;
    }

    public static void clearSelectedSongs(long u) { selectedSongs.remove(u); }
}
