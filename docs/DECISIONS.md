# Notification Service — ADR (Decisions)

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

## ADR-1: Multi-topic Kafka consumer

**Контекст:** Сервис должен слушать разные типы событий из разных топиков.

**Решение:** Один consumer group подписан на несколько топиков. Десериализация производится
по полю `eventType` внутри JSON — маршрутизация на уровне кода.

**Альтернатива:** Отдельный consumer на каждый топик → больше кода, но более гранулярный контроль.
Выбрали единый consumer для простоты MVP.

## ADR-2: Throttle через Redis SETEX

**Контекст:** Нужно предотвратить спам — не слать одно и то же уведомление чаще N минут.

**Решение:** Redis ключ `throttle:{ruleId}:{vehicleId}` с TTL = throttle_minutes.
Если ключ существует — пропускаем. Иначе — отправляем и ставим ключ.

**Pros:** Атомарно, быстро, автоматический cleanup через TTL.
**Cons:** При рестарте Redis — throttle сбрасывается (допустимо).

## ADR-3: Rate limiting per user per channel

**Контекст:** Защита от спама пользователю: email 100/час, sms 20/час.

**Решение:** Redis INCR + EXPIRE на ключ `rate:{userId}:{channel}:{hour}`.
Если counter > limit — не отправляем, пишем в историю со статусом `rate_limited`.

## ADR-4: Template Engine с {{переменными}}

**Контекст:** Шаблоны хранятся в PostgreSQL, переменные подставляются в runtime.

**Решение:** Простой string replace `{{vehicle_name}}` → actual value. Без полноценного
шаблонизатора (Mustache/Handlebars) на этапе MVP. При необходимости — заменим.

**Переменные:** `vehicle_name`, `geozone_name`, `speed`, `speed_limit`, `timestamp`,
`address`, `fuel_amount`, `sensor_value`, `sensor_type`.

## ADR-5: Каналы доставки как ZIO Layer

**Контекст:** 5 каналов с разными внешними зависимостями (SMTP, HTTP, Firebase).

**Решение:** Каждый канал — отдельный trait + Live реализация + ZIO Layer.
`DeliveryService` агрегирует все каналы, выбирает нужный по `Channel` enum.

**Pros:** Каналы легко мокать в тестах, легко добавлять новые.
