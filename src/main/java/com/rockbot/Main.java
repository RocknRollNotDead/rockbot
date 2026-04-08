package com.rockbot;

import com.rockbot.bot.RockBandBot;
import com.rockbot.db.DatabaseManager;
import com.rockbot.util.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Точка входа.
 *
 * Порядок запуска:
 *  1. Проверяем конфигурацию (.env)
 *  2. Инициализируем SQLite — создаём rockbot.db и все таблицы
 *  3. Регистрируем бота в Telegram
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (BotConfig.TOKEN == null || BotConfig.TOKEN.isBlank()) {
            log.error("BOT_TOKEN не задан. Создайте файл .env — смотрите .env.example");
            System.exit(1);
        }
        if (BotConfig.USERNAME == null || BotConfig.USERNAME.isBlank()) {
            log.error("BOT_USERNAME не задан. Создайте файл .env — смотрите .env.example");
            System.exit(1);
        }

        log.info("Запуск бота «{}» как @{}", BotConfig.BAND_NAME, BotConfig.USERNAME);

        // Создаём rockbot.db и все таблицы если не существуют
//        DatabaseManager.initializeDatabase();

        log.info("База данных готова.");

        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(new RockBandBot());
            log.info("Бот запущен! Ctrl+C для остановки.");
        } catch (TelegramApiException e) {
            log.error("Не удалось запустить бота", e);
            System.exit(1);
        }
    }
}
