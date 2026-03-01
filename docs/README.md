# 🔔 Notification Service

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

## Описание

Notification Service — сервис управления правилами уведомлений и доставки по каналам:
Email, SMS, Push (FCM), Telegram, Webhook.

Потребляет события из нескольких Kafka-топиков (`geozone-events`, `rule-violations`,
`device-status`), находит подходящие правила, рендерит шаблон и отправляет уведомления.

## Порт

| Назначение | Порт |
|-----------|------|
| REST API  | 8094 |

## Переменные окружения

| Переменная | По умолчанию | Описание |
|-----------|-------------|----------|
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tracker_notifications` | PostgreSQL JDBC URL |
| `DATABASE_USER` | `notifications` | DB user |
| `DATABASE_PASSWORD` | `notifications_pass` | DB password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `HTTP_PORT` | `8094` | REST API порт |
| `SMTP_HOST` | `smtp.gmail.com` | SMTP сервер |
| `SMTP_PORT` | `587` | SMTP порт |
| `SMTP_USERNAME` | — | SMTP логин |
| `SMTP_PASSWORD` | — | SMTP пароль |
| `SMTP_FROM` | `noreply@wayrecall.com` | Email отправителя |
| `SMS_PROVIDER` | `smsru` | SMS провайдер (smsru/twilio) |
| `SMS_API_KEY` | — | API ключ SMS шлюза |
| `TELEGRAM_BOT_TOKEN` | — | Токен Telegram бота |
| `FCM_CREDENTIALS_FILE` | — | Путь к Firebase credentials |

## Быстрый старт

```bash
# Локально через sbt
cd services/notification-service
sbt run

# Docker
docker build -t notification-service .
docker run -p 8094:8094 \
  -e KAFKA_BROKERS=kafka:9092 \
  -e DATABASE_URL=jdbc:postgresql://postgres:5432/tracker_notifications \
  notification-service
```

## Health Check

```bash
curl http://localhost:8094/health
# {"status":"ok","service":"notification-service","version":"1.0.0"}
```
