# 🎸 Инструкция по деплою Telegram-бота для музыкальной группы

Эта инструкция поможет вам развернуть собственного бота для управления музыкальной группой, даже если вы никогда не программировали.

## Что умеет бот?

- Хранить музыку, минуса, тексты песен
- Управлять альбомами и концертами
- Принимать фидбек от слушателей
- Делать рассылки участникам
- Управлять участниками группы (три уровня доступа: слушатель, участник, админ)

Полный список команд смотрите в [README.md](README.md)

---

## Подготовка

### 1. Создайте бота в Telegram

1. Откройте Telegram и найдите бота [@BotFather](https://t.me/BotFather)
2. Отправьте команду `/newbot`
3. Придумайте имя для бота (например: "My Rock Band Bot")
4. Придумайте username (должен заканчиваться на `bot`, например: `MyRockBandBot`)
5. BotFather пришлет вам **токен** - сохраните его (выглядит примерно так: `123456789:ABCDefGHIjklMNOpqrSTUvwxYZ`)

### 2. Узнайте свой Telegram ID

1. Найдите бота [@userinfobot](https://t.me/userinfobot)
2. Отправьте ему любое сообщение
3. Он пришлет ваш ID (например: `111111111`) - сохраните его

### 3. Форкните репозиторий

1. Нажмите кнопку **Fork** в правом верхнем углу этой страницы GitHub
2. Теперь у вас есть своя копия проекта

---

## Способ 1: Деплой на Railway.app (рекомендуется)

Railway - это платформа для хостинга приложений. Бесплатный план дает 500 часов работы в месяц (этого достаточно для круглосуточной работы бота).

### Шаг 1: Регистрация на Railway

1. Перейдите на [railway.app](https://railway.app)
2. Нажмите **Login** и войдите через GitHub
3. Подтвердите доступ к вашему GitHub аккаунту

### Шаг 2: Создание проекта

1. На главной странице Railway нажмите **New Project**
2. Выберите **Deploy from GitHub repo**
3. Выберите ваш форкнутый репозиторий `rockbot`
4. Railway автоматически начнет деплой

### Шаг 3: Настройка переменных окружения

1. В проекте Railway откройте вкладку **Variables**
2. Добавьте следующие переменные (нажимайте **New Variable** для каждой):

```
BOT_TOKEN=ваш_токен_от_BotFather
BOT_USERNAME=ваш_username_бота
BAND_NAME=Название вашей группы
ADMIN_IDS=ваш_telegram_id
```

Пример:
```
BOT_TOKEN=123456789:ABCDefGHIjklMNOpqrSTUvwxYZ
BOT_USERNAME=MyRockBandBot
BAND_NAME=The Rock Stars
ADMIN_IDS=111111111
```

Если администраторов несколько, перечислите их ID через запятую:
```
ADMIN_IDS=111111111,222222222,333333333
```

### Шаг 4: Перезапуск

1. После добавления переменных нажмите **Deploy** → **Restart**
2. Подождите 1-2 минуты
3. Откройте вкладку **Deployments** и проверьте, что статус **Success**

### Шаг 5: Проверка

1. Откройте Telegram и найдите вашего бота
2. Отправьте команду `/start`
3. Бот должен ответить главным меню

**Готово!** Ваш бот работает 24/7 на Railway.

---

## Способ 2: Деплой на Render.com

Render - альтернатива Railway с бесплатным планом (бот будет "засыпать" после 15 минут неактивности).

### Шаг 1: Регистрация

1. Перейдите на [render.com](https://render.com)
2. Нажмите **Get Started** и войдите через GitHub

### Шаг 2: Создание Web Service

1. На дашборде нажмите **New +** → **Web Service**
2. Выберите ваш репозиторий `rockbot`
3. Заполните настройки:
   - **Name**: любое имя (например: `my-rock-bot`)
   - **Region**: выберите ближайший регион
   - **Branch**: `master`
   - **Runtime**: `Docker`
   - **Instance Type**: `Free`

### Шаг 3: Переменные окружения

1. Прокрутите вниз до раздела **Environment Variables**
2. Добавьте переменные (как в Railway):

```
BOT_TOKEN=ваш_токен
BOT_USERNAME=ваш_username
BAND_NAME=название_группы
ADMIN_IDS=ваш_id
```

### Шаг 4: Деплой

1. Нажмите **Create Web Service**
2. Подождите 5-10 минут (первая сборка может быть долгой)
3. Проверьте логи - должно быть "Bot started successfully"

**Важно**: На бесплатном плане Render бот будет останавливаться после 15 минут неактивности. Для круглосуточной работы используйте Railway или платный план.

---

## Способ 3: Деплой на собственном сервере (VPS)

Если у вас есть свой сервер (VPS) с Ubuntu/Debian.

### Требования

- Ubuntu 20.04+ или Debian 11+
- Минимум 512 MB RAM
- Java 21

### Шаг 1: Подключение к серверу

```bash
ssh ваш_пользователь@ip_адрес_сервера
```

### Шаг 2: Установка Java 21

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven git
```

### Шаг 3: Клонирование репозитория

```bash
cd ~
git clone https://github.com/ваш_username/rockbot.git
cd rockbot
```

### Шаг 4: Настройка переменных

```bash
cp .env.example .env
nano .env
```

Заполните файл `.env`:
```
BOT_TOKEN=ваш_токен
BOT_USERNAME=ваш_username
BAND_NAME=название_группы
ADMIN_IDS=ваш_id
```

Сохраните (Ctrl+O, Enter, Ctrl+X)

### Шаг 5: Сборка проекта

```bash
mvn clean package -DskipTests
```

### Шаг 6: Запуск бота

```bash
java -jar target/rock-band-bot-1.0-SNAPSHOT.jar
```

Бот запустится. Для остановки нажмите Ctrl+C.

### Шаг 7: Автозапуск (опционально)

Чтобы бот работал постоянно и перезапускался после перезагрузки сервера:

```bash
sudo nano /etc/systemd/system/rockbot.service
```

Вставьте:
```ini
[Unit]
Description=Rock Band Telegram Bot
After=network.target

[Service]
Type=simple
User=ваш_пользователь
WorkingDirectory=/home/ваш_пользователь/rockbot
ExecStart=/usr/bin/java -jar /home/ваш_пользователь/rockbot/target/rock-band-bot-1.0-SNAPSHOT.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Замените `ваш_пользователь` на ваше имя пользователя.

Активируйте сервис:
```bash
sudo systemctl daemon-reload
sudo systemctl enable rockbot
sudo systemctl start rockbot
```

Проверка статуса:
```bash
sudo systemctl status rockbot
```

Просмотр логов:
```bash
sudo journalctl -u rockbot -f
```

---

## Способ 4: Локальный запуск (для тестирования)

Если хотите просто протестировать бота на своем компьютере.

### Windows

1. Установите [Java 21](https://adoptium.net/temurin/releases/?version=21)
2. Установите [Maven](https://maven.apache.org/download.cgi)
3. Скачайте и распакуйте репозиторий
4. Создайте файл `.env` (скопируйте `.env.example` и заполните)
5. Откройте командную строку в папке проекта:
```cmd
mvn clean package -DskipTests
java -jar target\rock-band-bot-1.0-SNAPSHOT.jar
```

### macOS / Linux

1. Установите Java 21 и Maven:
```bash
# macOS (через Homebrew)
brew install openjdk@21 maven

# Linux
sudo apt install openjdk-21-jdk maven
```

2. Клонируйте репозиторий:
```bash
git clone https://github.com/ваш_username/rockbot.git
cd rockbot
```

3. Настройте `.env`:
```bash
cp .env.example .env
nano .env  # или используйте любой текстовый редактор
```

4. Соберите и запустите:
```bash
mvn clean package -DskipTests
java -jar target/rock-band-bot-1.0-SNAPSHOT.jar
```

---

## Обновление бота

### На Railway/Render

1. Внесите изменения в код (или получите обновления из основного репозитория)
2. Закоммитьте и запушьте в GitHub:
```bash
git add .
git commit -m "Update bot"
git push
```
3. Railway/Render автоматически пересоберут и задеплоят новую версию

### На VPS

```bash
cd ~/rockbot
git pull
mvn clean package -DskipTests
sudo systemctl restart rockbot
```

---

## Решение проблем

### Бот не отвечает

1. Проверьте, что бот запущен (на Railway/Render смотрите логи)
2. Убедитесь, что `BOT_TOKEN` правильный
3. Проверьте, что ваш ID есть в `ADMIN_IDS`

### Ошибка "Unauthorized"

Неправильный `BOT_TOKEN`. Проверьте токен у @BotFather.

### Бот не видит команды

Убедитесь, что `BOT_USERNAME` указан без `@` (правильно: `MyRockBandBot`, неправильно: `@MyRockBandBot`)

### База данных не сохраняется (Railway/Render)

На бесплатных планах файловая система эфемерная. Для постоянного хранения данных нужно:
- Использовать внешнюю БД (PostgreSQL)
- Или использовать платный план с persistent storage

### Логи на Railway

1. Откройте проект
2. Перейдите на вкладку **Deployments**
3. Нажмите на последний деплой
4. Откройте **View Logs**

---

## Дополнительные настройки

### Импорт песен из файлов

Если хотите автоматически импортировать песни из аудиофайлов:

1. Добавьте переменную `IMPORT_DIR` с путем к папке с музыкой
2. Положите файлы `.mp3`, `.ogg` или `.flac` в эту папку
3. Формат имен файлов: `Название.mp3` или `Название - Альбом.mp3`
4. Выполните команду `/updateimport` в боте

### Несколько администраторов

В `ADMIN_IDS` перечислите ID через запятую без пробелов:
```
ADMIN_IDS=111111111,222222222,333333333
```

### Кастомизация уведомлений

По умолчанию уведомления о запросах на вступление приходят первому админу. Чтобы изменить получателя:
1. Добавьте переменную `REQUEST_RECIPIENT_ID=ваш_id`
2. Или используйте команду `/setrecipient <userId>` в боте

---

## Поддержка

Если возникли проблемы:
1. Проверьте [Issues](../../issues) - возможно, кто-то уже решил вашу проблему
2. Создайте новый Issue с описанием проблемы и логами

---

## Лицензия

Этот проект распространяется свободно. Используйте для своих групп!

---

**Удачи с вашим ботом! 🎸🤘**
