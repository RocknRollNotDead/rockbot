package com.rockbot.bot;

import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;

import com.rockbot.db.DatabaseManager;
import com.rockbot.handler.CallbackHandler;
import com.rockbot.handler.MessageHandler;
import com.rockbot.util.BotConfig;
import com.rockbot.util.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

/**
 * Главный класс бота — точка входа для Telegram API.
 *
 * ── Методы работы с сообщениями ─────────────────────────────────────────
 *
 *  sendText(chatId, text)
 *    Отправляет новое текстовое сообщение.
 *    Автоматически увеличивает msgsSinceNav и ставит navStale = true,
 *    потому что в чате появилось новое сообщение выше nav-сообщения.
 *
 *  sendAudioMsg(chatId, userId, fileId, caption)
 *    Отправляет аудиофайл.
 *    Тоже увеличивает msgsSinceNav и ставит navStale = true.
 *    userId передаётся отдельно, потому что chatId == userId только в личных чатах.
 *
 *  sendNotification(chatId, text, kbd)
 *    Отправляет уведомление с inline-клавиатурой (например, запрос на участие).
 *    Тоже увеличивает счётчик — получатель видит новое сообщение.
 *
 *  sendWithKeyboard(chatId, text, kbd)
 *    Отправляет сообщение с клавиатурой.
 *    НЕ инкрементирует счётчик — используется только для нового nav-сообщения
 *    (вызывается из resendNav и cmdStart).
 *
 *  editNav(chatId, messageId, text, kbd)
 *    Редактирует существующее nav-сообщение на месте.
 *    НЕ инкрементирует счётчик — сообщение остаётся на том же месте.
 *
 *  stripNavKeyboard(chatId, userId)
 *    Убирает кнопки у старого nav-сообщения перед пересылкой.
 *
 *  resendNav(chatId, userId, text, kbd)
 *    Пересылает nav в конец чата:
 *      1. Убирает кнопки у старого nav
 *      2. Отправляет новое nav (sendWithKeyboard — без инкремента)
 *      3. Сохраняет новый messageId
 *      4. Сбрасывает msgsSinceNav → 0 и navStale → false
 */
public class RockBandBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(RockBandBot.class);

    private final MessageHandler  messageHandler;
    private final CallbackHandler callbackHandler;

    public RockBandBot() {
        super(BotConfig.TOKEN);
        this.messageHandler  = new MessageHandler(this);
        this.callbackHandler = new CallbackHandler(this);
    }

    @Override public String getBotUsername() { return BotConfig.USERNAME; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) callbackHandler.handle(update.getCallbackQuery());
            else if (update.hasMessage())  messageHandler.handle(update.getMessage());
        } catch (Exception e) { log.error("Ошибка в onUpdateReceived", e); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ИСХОДЯЩИЕ СООБЩЕНИЯ (не-nav) — ВСЕГДА инкрементируют счётчик
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Отправляет новое текстовое сообщение.
     *
     * Сразу после отправки:
     *  - msgsSinceNav(chatId) += 1   — в чате появилось новое сообщение после nav
     *  - navStale(chatId) = true     — при следующем нажатии кнопки nav будет переслан
     *
     * chatId == userId в личных чатах (стандартная схема для этого бота).
     */
    /*public int sendText(long chatId, String text) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(text);
            msg.setParseMode("Markdown");
            int msgId = execute(msg).getMessageId();
            // Новое сообщение появилось в чате — nav теперь не последний
            UserSession.incMsgsSinceNav(chatId);
            UserSession.markNavStale(chatId);
            return msgId;
        } catch (Exception e) { log.error("sendText chatId={}", chatId, e); return 0; }
    }*/
    public int sendText(long chatId, String text) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(text);
            msg.setParseMode("Markdown");
            int msgId = execute(msg).getMessageId();
            UserSession.incMsgsSinceNav(chatId);
            UserSession.markNavStale(chatId);
            return msgId;
        } catch (Exception e) {
            // Если Markdown-парсинг упал (незакрытый тег из пользовательского текста)
            // — повторяем без форматирования. Plain text всегда безопасен.
            if (e.getMessage() != null && e.getMessage().contains("can't parse entities")) {
                log.warn("sendText Markdown failed for chatId={}, retrying as plain text", chatId);
                try {
                    SendMessage msg = new SendMessage();
                    msg.setChatId(chatId);
                    msg.setText(text);
                    // Без parseMode — plain text
                    int msgId = execute(msg).getMessageId();
                    UserSession.incMsgsSinceNav(chatId);
                    UserSession.markNavStale(chatId);
                    return msgId;
                } catch (Exception e2) { log.error("sendText plain fallback chatId={}", chatId, e2); return 0; }
            }
            log.error("sendText chatId={}", chatId, e); return 0;
        }
    }



    /**
     * Отправляет аудиофайл новым сообщением.
     *
     * userId передаётся явно (не через chatId), потому что этот метод может
     * вызываться из NavigationHandler, где userId и chatId оба известны.
     * Логика счётчика — та же, что в sendText().
     */
    public void sendAudioMsg(long chatId, long userId, String fileId, String caption) {
        try {
            SendAudio audio = new SendAudio();
            audio.setChatId(chatId);
            // Telegram кэширует файлы по file_id — повторная отправка мгновенная
            audio.setAudio(new InputFile(fileId));
            audio.setCaption(caption);
            execute(audio);
            // Аудиофайл — тоже новое сообщение, nav «уходит вверх»
            UserSession.incMsgsSinceNav(userId);
            UserSession.markNavStale(userId);
        } catch (Exception e) { log.error("sendAudioMsg chatId={}", chatId, e); }
    }

    /**
     * Отправляет уведомление с inline-клавиатурой (не nav-сообщение).
     * Используется для запросов на участие, ответов от adminа и т.п.
     *
     * Инкрементирует счётчик, потому что получатель видит новое сообщение.
     */
    public void sendNotification(long chatId, String text, InlineKeyboardMarkup kbd) {
        try {
            /*SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(text);
            msg.setParseMode("Markdown");
            msg.setReplyMarkup(kbd);
            execute(msg);
            // Уведомление — тоже новое сообщение в чате получателя
            UserSession.incMsgsSinceNav(chatId);
            UserSession.markNavStale(chatId);*/

            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(text);
            // Намеренно НЕ устанавливаем parseMode — уведомления содержат
            // пользовательский текст (username, сообщение запроса), который может
            // включать незакрытые спецсимволы Markdown и ломать парсинг Telegram.
            // Plain text всегда безопасен.
            msg.setReplyMarkup(kbd);
            execute(msg);
            // Уведомление — тоже новое сообщение в чате получателя
            UserSession.incMsgsSinceNav(chatId);
            UserSession.markNavStale(chatId);


        } catch (Exception e) { log.error("sendNotification chatId={}", chatId, e); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NAV-СООБЩЕНИЕ — НЕ инкрементирует счётчик (это и есть позиция nav)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Отправляет новое nav-сообщение (с кнопками) и возвращает его messageId.
     *
     * НЕ инкрементирует счётчик — это сообщение И ЕСТЬ nav,
     * его позиция в чате запоминается как «где сейчас кнопки».
     * Вызывается только из cmdStart() и resendNav().
     */
    public int sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup kbd) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(text);
            msg.setParseMode("Markdown");
            msg.setReplyMarkup(kbd);
            // Счётчик НЕ трогаем — это новое положение nav, не "посторонее" сообщение
            return execute(msg).getMessageId();
        } catch (Exception e) { log.error("sendWithKeyboard chatId={}", chatId, e); return 0; }
    }

    /**
     * Редактирует текущее nav-сообщение на месте.
     * Не создаёт нового сообщения → счётчик не меняется.
     * Используется при нажатии кнопок (когда nav не нужно пересылать).
     */
    public void editNav(long chatId, int messageId, String text, InlineKeyboardMarkup kbd) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(text);
            edit.setParseMode("Markdown");
            edit.setReplyMarkup(kbd);
            execute(edit);
            // editNav не создаёт нового сообщения — счётчик не меняем
        } catch (Exception e) {
            // "message is not modified" — нормально, если экран не изменился
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) return;
            log.error("editNav chatId={} msgId={}", chatId, messageId, e);
        }
    }

    // RockBandBot.java

    /**
     * Убирает клавиатуру у произвольного сообщения (не обязательно nav).
     * Используется для уведомлений с кнопками после обработки запроса.
     */
    public void removeKeyboard(long chatId, int messageId) {
        try {
            EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setReplyMarkup(new InlineKeyboardMarkup(List.of()));
            execute(edit);
        } catch (Exception e) {
            log.debug("removeKeyboard chatId={} msgId={}: {}", chatId, messageId,
                    e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
        }
    }

    /**
     * Редактирует текст произвольного сообщения (не nav).
     * Используется для уведомлений — меняем текст и убираем кнопки.
     */
    public void editMessage(long chatId, int messageId, String text) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(text);
            // Без parseMode — текст содержит пользовательские данные
            execute(edit);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) return;
            log.error("editMessage chatId={} msgId={}", chatId, messageId, e);
        }
    }

    /**
     * Убирает клавиатуру у текущего nav-сообщения.
     * Вызывается перед тем, как переслать nav в конец чата,
     * чтобы старые кнопки не висели посреди чата и не вводили в заблуждение.
     */
    /*public void stripNavKeyboard(long chatId, long userId) {
        int oldMsgId = UserSession.getNavMessageId(userId);
        if (oldMsgId <= 0) return;
        try {
            EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
            edit.setChatId(chatId);
            edit.setMessageId(oldMsgId);
            // Пустой список рядов = убрать клавиатуру
            edit.setReplyMarkup(new InlineKeyboardMarkup(List.of()));
            execute(edit);
        } catch (Exception e) {
            // Игнорируем — сообщение могло устареть (> 48 часов) или уже не иметь кнопок
            log.debug("stripNavKeyboard chatId={} msgId={}: {}", chatId, oldMsgId,
                      e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
        }
    }*/

    /**
     * Пересылает nav в конец чата.
     * Вызывается из smartNav() когда navStale=true или msgsSinceNav >= 2.
     *
     * Алгоритм:
     *  1. stripNavKeyboard  — убираем кнопки у старого nav (оно осталось посреди чата)
     *  2. sendWithKeyboard  — отправляем новое nav в конец чата (НЕ инкрементирует)
     *  3. setNavMessageId   — запоминаем новый messageId
     *  4. resetMsgsSinceNav — сбрасываем счётчик (nav снова последний)
     *  5. clearNavStale     — сбрасываем флаг (nav свежий)
     */
    /*public void resendNav(long chatId, long userId, String text, InlineKeyboardMarkup kbd) {
        stripNavKeyboard(chatId, userId);
        int newMsgId = sendWithKeyboard(chatId, text, kbd);
        if (newMsgId > 0) {
            UserSession.setNavMessageId(userId, newMsgId);
            UserSession.resetMsgsSinceNav(userId); // nav снова в конце — сбрасываем счётчик
            UserSession.clearNavStale(userId);     // nav свежий — сбрасываем флаг
        }
    }*/

    /**
     * Убирает клавиатуру у nav-сообщения и заменяет текст на нейтральный.
     * Вызывается перед пересылкой nav в конец чата (resendNav).
     *
     * @param newText  текст для замены, например "🎸 Rock Band". Если null — оставляем старый текст.
     */
    public void stripNavKeyboard(long chatId, long userId, String newText) {
        int oldMsgId = UserSession.getNavMessageId(userId);
        if (oldMsgId <= 0) return;

        if (newText != null) {
            // Меняем и текст, и убираем клавиатуру за один вызов
            try {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(chatId);
                edit.setMessageId(oldMsgId);
                edit.setText(newText);
                // Без parseMode — текст не содержит форматирования
                execute(edit);
            } catch (Exception e) {
                log.debug("stripNavKeyboard (text) chatId={} msgId={}: {}",
                        chatId, oldMsgId, e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
            }
        } else {
            // Убираем только клавиатуру
            try {
                EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
                edit.setChatId(chatId);
                edit.setMessageId(oldMsgId);
                edit.setReplyMarkup(new InlineKeyboardMarkup(List.of()));
                execute(edit);
            } catch (Exception e) {
                log.debug("stripNavKeyboard chatId={} msgId={}: {}",
                        chatId, oldMsgId, e.getMessage() != null ? e.getMessage().split("\n")[0] : "");
            }
        }
    }

    public void resendNav(long chatId, long userId, String text, InlineKeyboardMarkup kbd) {
        // Передаём только название группы — нейтральный текст без "Выберите раздел"
        stripNavKeyboard(chatId, userId, "\uD83D\uDE08 " + BotConfig.BAND_NAME + " \uD83D\uDE08");
        int newMsgId = sendWithKeyboard(chatId, text, kbd);
        if (newMsgId > 0) {
            UserSession.setNavMessageId(userId, newMsgId);
            UserSession.resetMsgsSinceNav(userId);
            UserSession.clearNavStale(userId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // РАССЫЛКА
    // ════════════════════════════════════════════════════════════════════════

    /** Отправляет сообщение всем подписчикам через sendText (с инкрементом) */
    public void broadcastToSubscribers(String message) {
        List<Long> ids = DatabaseManager.getAllSubscriberIds();
        log.info("Рассылка {} подписчикам", ids.size());
        for (long uid : ids) sendText(uid, message);
    }
}
