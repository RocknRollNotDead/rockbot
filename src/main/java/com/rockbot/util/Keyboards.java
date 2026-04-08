package com.rockbot.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyboards — все inline-клавиатуры бота в одном месте.
 *
 * callback_data (≤ 64 байта):
 *   h             — главное меню
 *   sl_<page>     — список песен
 *   s_<id>        — карточка песни
 *   lyr_<id>      — текст
 *   cho_<id>      — меню аккордов
 *   ch_<s>_<c>    — аккорды инструмента
 *   ins_<id>      — инструментал
 *   his_<id>      — история
 *   edt_<id>      — меню редактирования
 *   et/ea/eau/el/ec/ei/eh_<id> — поля редактирования
 *   del_<id>      — подтверждение удаления
 *   delok_<id>    — удалить
 *   ev            — концерты
 *   rh            — репетиции
 *   alb           — альбомы (список)
 *   al_<albumId>  — песни альбома
 *   srch          — поиск
 *   req_member    — запросить статус участника
 *   mrok_<reqId>  — одобрить запрос
 *   mrno_<reqId>  — отклонить запрос
 *   mm_<userId>   — карточка участника
 *   mmd_<userId>  — подтверждение удаления участника
 *   mmdo_<userId> — удалить участника
 *   mmb           — назад к списку участников
 *   ublk          — список заблокированных
 *   ublk_<uid>    — подтверждение разблокировки
 *   ublko_<uid>   — разблокировать
 *   ats_<songId>  — переключить выбор песни при создании альбома
 *   albs          — сохранить альбом
 *   albc          — отменить создание альбома
 *   wiz_song      — запустить добавление песни
 *   wiz_album     — запустить добавление альбома
 *   wiz_event     — запустить добавление концерта
 *   wiz_rehearsal — запустить добавление репетиции
 *   vote_<p>_<o>  — голосование
 *   noop          — кнопка-индикатор
 */
public class Keyboards {

    public static final int PAGE_SIZE = 8;

    private static InlineKeyboardButton btn(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton(); b.setText(text); b.setCallbackData(data); return b;
    }
    @SafeVarargs
    private static List<InlineKeyboardButton> row(InlineKeyboardButton... btns) { return List.of(btns); }
    public static InlineKeyboardMarkup markup(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup(); m.setKeyboard(rows); return m;
    }
    private static String trunc(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max - 1) + "…");
    }

    // ── Главное меню ──────────────────────────────────────────────────────

    /**
     * @param isStaff    MEMBER или ADMIN
     * @param isListener LISTENER
     * @param hasAlbums  показывать ли кнопку «Альбомы»
     * @param isSubscribed подписан ли пользователь (влияет на кнопку подписки)
     */
    public static InlineKeyboardMarkup home(boolean isStaff, boolean isListener,
                                            boolean hasAlbums, boolean isSubscribed) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(btn("🎵 Все песни", "sl_0"), btn("🔍 Поиск", "srch")));
        List<InlineKeyboardButton> r2 = new ArrayList<>();
        if(!isStaff) {
            r2.add(btn("📅 Концерты", "ev"));

        } else {
            r2.add(btn("📅 Концерты", "ev"));
            r2.add(btn("🎸 Репетиции", "rh"));
        }
//        if (hasAlbums) r2.add(btn("💿 Альбомы",  "alb"));
        rows.add(r2);
        if (hasAlbums) rows.add(row(btn("💿 Альбомы",  "alb")));
//        if (isStaff) rows.add(row(btn("🎸 Репетиции", "rh")));
        // Подписка / отписка
        /*if (isListener) {
            if (!isSubscribed) rows.add(row(btn("🔔 Подписаться на новости", "sub")));
            else               rows.add(row(btn("🔕 Отписаться", "unsub")));
            rows.add(row(btn("📨 Запросить статус участника", "req_member")));
        }*/
        return markup(rows);
    }

    // ── Список песен ──────────────────────────────────────────────────────

    public static InlineKeyboardMarkup songList(List<String[]> songs, int page, int totalPages, boolean canAdd) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] s : songs) {
            String icon = s[3].isBlank() ? "🔇" : "🎵";
            rows.add(row(btn(icon + " " + trunc(s[1], 28) + (s[2].isBlank() ? "" : " · " + trunc(s[2], 14)), "s_" + s[0])));
        }
        if (totalPages > 1) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (page > 0)              nav.add(btn("◀", "sl_" + (page - 1)));
            nav.add(btn((page + 1) + "/" + totalPages, "noop"));
            if (page < totalPages - 1) nav.add(btn("▶", "sl_" + (page + 1)));
            rows.add(nav);
        }
        if (canAdd) rows.add(row(btn("➕ Добавить песню", "wiz_song")));
        rows.add(row(btn("🔙 Главное меню", "h")));
        return markup(rows);
    }

    // ── Список альбомов ───────────────────────────────────────────────────

    /** albums = [[id, name, songCount], ...] */
    public static InlineKeyboardMarkup albumList(List<String[]> albums, boolean canAdd) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] a : albums)
            rows.add(row(btn("💿 " + trunc(a[1], 26) + " (" + a[2] + " шт.)", "al_" + a[0])));
        if (canAdd) rows.add(row(btn("➕ Добавить альбом", "wiz_album")));
        rows.add(row(btn("🔙 Главное меню", "h")));
        return markup(rows);
    }

    /** Песни в альбоме */
    public static InlineKeyboardMarkup albumSongList(List<String[]> songs) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] s : songs) {
            String icon = s[3].isBlank() ? "🔇" : "🎵";
            rows.add(row(btn(icon + " " + trunc(s[1], 32), "s_" + s[0])));
        }
        rows.add(row(btn("🔙 К альбомам", "alb")));
        return markup(rows);
    }

    // ── Создание альбома: интерактивный выбор песен ───────────────────────

    /**
     * Показывает все песни как кнопки-переключатели.
     * Выбранные показывают: «✅ 1 Название» (1 = позиция в альбоме).
     * Невыбранные: «  Название»
     *
     * @param allSongs   все песни [[id, title, ...], ...]
     * @param userId     для получения текущего выбора из UserSession
     */
    public static InlineKeyboardMarkup albumSongPicker(List<String[]> allSongs, long userId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] s : allSongs) {
            long songId = Long.parseLong(s[0]);
            int  pos    = UserSession.getSongPosition(userId, songId);
            String label = pos > 0
                ? "✅ " + pos + " " + trunc(s[1], 25)
                : "     " + trunc(s[1], 28);
            rows.add(row(btn(label, "ats_" + s[0])));
        }
        rows.add(row(btn("💾 Сохранить альбом", "albs"), btn("❌ Отмена", "albc")));
        return markup(rows);
    }

    // ── Карточка песни ────────────────────────────────────────────────────

    public static InlineKeyboardMarkup songDetail(long songId, boolean hasLyrics, boolean hasChords,
                                                   boolean hasInstr, boolean hasHistory,
                                                   boolean canEdit, int backPage) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r1 = new ArrayList<>();
        if (hasLyrics) r1.add(btn("📝 Текст",   "lyr_" + songId));
        if (hasChords) r1.add(btn("🎸 Аккорды", "cho_" + songId));
        if (!r1.isEmpty()) rows.add(r1);
        List<InlineKeyboardButton> r2 = new ArrayList<>();
        if (hasInstr)   r2.add(btn("🎼 Инструментал", "ins_" + songId));
        if (hasHistory) r2.add(btn("📖 История",       "his_" + songId));
        if (!r2.isEmpty()) rows.add(r2);
        if (canEdit) rows.add(row(btn("✏️ Редактировать", "edt_" + songId)));
        rows.add(row(btn("🔙 К списку", "sl_" + backPage)));
        return markup(rows);
    }

    // ── Меню редактирования ───────────────────────────────────────────────

    public static InlineKeyboardMarkup editMenu(long songId) {
        return markup(List.of(
            row(btn("📝 Название",      "et_"  + songId)/*, btn("💿 Альбом",       "ea_"  + songId)*/),
            row(btn("🎵 Аудиофайл",    "eau_" + songId), btn("🎼 Инструментал", "ei_"  + songId)),
            row(btn("📄 Текст песни",   "el_"  + songId), btn("🎸 Аккорды",      "ec_"  + songId)),
            row(btn("📖 История",       "eh_"  + songId)),
            row(btn("🗑 Удалить",       "del_" + songId)),
            row(btn("🔙 К песне",       "s_"   + songId))
        ));
    }

    public static InlineKeyboardMarkup deleteConfirm(long songId) {
        return markup(List.of(
            row(btn("✅ Да, удалить", "delok_" + songId), btn("❌ Отмена", "edt_" + songId))
        ));
    }

    // ── Выбор инструмента ────────────────────────────────────────────────

    public static InlineKeyboardMarkup chordsInstruments(long songId, List<String[]> chords) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (String[] c : chords) {
            row.add(btn(c[1], "ch_" + songId + "_" + c[0]));
            if (row.size() == 2) { rows.add(new ArrayList<>(row)); row.clear(); }
        }
        if (!row.isEmpty()) rows.add(row);
        rows.add(row(btn("🔙 К песне", "s_" + songId)));
        return markup(rows);
    }

    // ── Концерты ─────────────────────────────────────────────────────────

    public static InlineKeyboardMarkup eventList(boolean canAdd) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (canAdd) rows.add(row(btn("➕ Добавить концерт", "wiz_event")));
        rows.add(row(btn("🔙 Главное меню", "h")));
        return markup(rows);
    }

    // ── Репетиции ─────────────────────────────────────────────────────────

    public static InlineKeyboardMarkup rehearsalList(boolean canAdd) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (canAdd) rows.add(row(btn("➕ Добавить репетицию", "wiz_rehearsal")));
        rows.add(row(btn("🔙 Главное меню", "h")));
        return markup(rows);
    }

    // ── Участники ─────────────────────────────────────────────────────────

    /** Список участников как кнопки */
    public static InlineKeyboardMarkup memberList(List<String[]> members) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] m : members)
            rows.add(row(btn("👤 " + trunc(m[1], 32), "mm_" + m[0])));
        rows.add(row(btn("🔙 Главное меню", "h")));
        return markup(rows);
    }

    /** Карточка участника с кнопкой удаления */
    public static InlineKeyboardMarkup memberCard(long userId) {
        return markup(List.of(
            row(btn("🗑 Удалить участника", "mmd_" + userId)),
            row(btn("🔙 К списку", "mmb"))
        ));
    }

    /** Подтверждение удаления участника */
    public static InlineKeyboardMarkup memberDeleteConfirm(long userId) {
        return markup(List.of(
            row(btn("✅ Да", "mmdo_" + userId), btn("🔙 Назад", "mm_" + userId))
        ));
    }

    // ── Заблокированные ───────────────────────────────────────────────────

    /** Список заблокированных пользователей как кнопки */
    public static InlineKeyboardMarkup blockedList(List<String[]> blocked) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] b : blocked)
            rows.add(row(btn("🔒 " + trunc(b[1], 30) + " до " + b[2], "ublk_" + b[0])));
        rows.add(row(btn("🔙 Главное меню", "h")));
        return markup(rows);
    }

    /** Подтверждение разблокировки */
    public static InlineKeyboardMarkup unblockConfirm(long userId) {
        return markup(List.of(
            row(btn("✅ Да", "ublko_" + userId), btn("🔙 Назад", "ublk"))
        ));
    }

    // ── Запросы на участие ────────────────────────────────────────────────

    public static InlineKeyboardMarkup memberRequestActions(long reqId) {
        return markup(List.of(
            row(btn("✅ Принять", "mrok_" + reqId), btn("❌ Отклонить", "mrno_" + reqId))
        ));
    }

    // ── Ожидание ввода ────────────────────────────────────────────────────

    public static InlineKeyboardMarkup inputCancel(String target) {
        return markup(List.of(row(btn("❌ Отмена", target))));
    }

    // ── Кнопки «Назад» ────────────────────────────────────────────────────

    public static InlineKeyboardMarkup backToSong(long songId) {
        return markup(List.of(row(btn("🔙 К песне", "s_" + songId))));
    }
    public static InlineKeyboardMarkup backToHome() {
        return markup(List.of(row(btn("🔙 Главное меню", "h"))));
    }

    // ── Опрос ─────────────────────────────────────────────────────────────

    public static InlineKeyboardMarkup pollVote(long pollId, List<String[]> options) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] o : options)
            rows.add(row(btn(o[1] + "  (" + o[2] + " гол.)", "vote_" + pollId + "_" + o[0])));
        return markup(rows);
    }

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
}
