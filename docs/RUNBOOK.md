# Notification Service — Runbook

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

## Запуск

### SBT (разработка)

```bash
cd services/notification-service
sbt run
```

### Docker

```bash
docker build -t notification-service .
docker run -p 8094:8094 \
  -e KAFKA_BROKERS=localhost:9092 \
  -e DATABASE_URL=jdbc:postgresql://localhost:5432/tracker_notifications \
  -e DATABASE_USER=notifications \
  -e DATABASE_PASSWORD=notifications_pass \
  -e SMTP_HOST=smtp.gmail.com \
  -e SMTP_USERNAME=user@gmail.com \
  -e SMTP_PASSWORD=app-password \
  notification-service
```

### Health Check

```bash
curl http://localhost:8094/health
```

## Типичные ошибки

### 1. Kafka consumer lag растёт

**Симптом:** история уведомлений отстаёт от событий.

**Причина:** медленная доставка (SMTP timeout, SMS gateway lag).

**Решение:**
- Проверить внешние API (SMTP, SMS gateway)
- Увеличить `parallelism` в consumer
- Отключить неработающий канал через конфиг

### 2. Rate limit срабатывает слишком часто

**Симптом:** статус `rate_limited` в истории уведомлений.

**Причина:** слишком много событий для одного пользователя.

**Решение:**
- Увеличить `throttle_minutes` в правилах
- Пересмотреть условия правил (слишком широкие фильтры)

### 3. Email не доходит

**Симптом:** статус `failed`, error `Connection refused` / `Authentication failed`.

**Решение:**
- Проверить SMTP credentials
- Проверить TLS настройки
- Для Gmail: включить App Passwords, не обычный пароль

### 4. Telegram бот не отвечает

**Симптом:** статус `failed`, error `Forbidden: bot was blocked by the user`.

**Решение:**
- Пользователь заблокировал бота — уведомить администратора
- Проверить `TELEGRAM_BOT_TOKEN`
- Проверить что бот добавлен в чат

## Мониторинг

### Ключевые метрики

| Метрика | Описание | Алерт порог |
|---------|----------|-------------|
| `ns_events_received_total` | Входящие события | — |
| `ns_notifications_sent_total{status}` | Отправленные по каналам | failed > 10/5min |
| `ns_throttled_total` | Пропущено по throttle | — |
| `ns_rate_limited_total` | Заблокировано rate limit | > 50/hour |
| `ns_delivery_duration_seconds` | Латентность доставки | p99 > 10s |

### Redis отладка

```bash
# Проверить throttle
redis-cli GET "throttle:1:42"

# Проверить rate limit
redis-cli GET "rate:5:email:2026-03-01T10"

# Посмотреть все throttle ключи
redis-cli KEYS "throttle:*"
```
