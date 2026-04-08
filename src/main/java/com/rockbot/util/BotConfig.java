package com.rockbot.util;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.*;
import java.util.stream.Collectors;

/** Загружает конфигурацию из .env один раз при старте. */
public class BotConfig {

    private static final Dotenv env = Dotenv.configure().ignoreIfMissing().load();

    public static final String TOKEN     = env.get("BOT_TOKEN");
    public static final String USERNAME  = env.get("BOT_USERNAME");
    public static final String BAND_NAME = env.get("BAND_NAME", "Наша Группа");

    /** Директория для автоматического импорта (может быть пустой) */
    public static final String IMPORT_DIR = env.get("IMPORT_DIR", "");

    private static final Set<Long> ADMIN_IDS;
    private static final Set<Long> USERS_WHO_GET_NOTIFICATION;// = env.get("PEOPLE_WHO_GET_NOTIFICATION");

    /** ID получателя запросов из .env (0 = не задан, используем первого admin) */
    private static final long ENV_REQUEST_RECIPIENT;

    static {
        String raw = env.get("ADMIN_IDS", "");
        ADMIN_IDS = Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).collect(Collectors.toCollection(LinkedHashSet::new));

        String recip = env.get("REQUEST_RECIPIENT_ID", "");
        long recipId = 0;
        try { if (!recip.isBlank()) recipId = Long.parseLong(recip.trim()); }
        catch (Exception ignored) {}
        ENV_REQUEST_RECIPIENT = recipId;

        String raw1 = env.get("PEOPLE_WHO_GET_NOTIFICATION", "");
        USERS_WHO_GET_NOTIFICATION = Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).collect(Collectors.toCollection(LinkedHashSet::new));


    }

    public static Role getRole(long userId) {
        if (ADMIN_IDS.contains(userId)) return Role.ADMIN;
        if (com.rockbot.db.DatabaseManager.isMember(userId)) return Role.MEMBER;
        return Role.LISTENER;
    }

    public static boolean isAdmin(long userId)  { return ADMIN_IDS.contains(userId); }
    public static boolean canEdit(long userId)  { return getRole(userId) != Role.LISTENER; }
    public static Set<Long> getAdminIds()       { return Collections.unmodifiableSet(ADMIN_IDS); }

    public static boolean isUserGetNot(long userId) { return USERS_WHO_GET_NOTIFICATION.contains(userId); }

    /**
     * Возвращает актуального получателя запросов на вступление.
     * Порядок: DB settings → .env → первый admin.
     */
    public static long getRequestRecipient() {
        long fromDb = com.rockbot.db.DatabaseManager.getSettingLong("request_recipient_id", 0L);
        if (fromDb > 0) return fromDb;
        if (ENV_REQUEST_RECIPIENT > 0) return ENV_REQUEST_RECIPIENT;
        return ADMIN_IDS.isEmpty() ? 0L : ADMIN_IDS.iterator().next();
    }
}
