# Notification Service — Data Model

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

## PostgreSQL: `tracker_notifications`

### Таблица: `notification_rules`

Правила уведомлений — условия, фильтры, каналы, получатели.

| Поле | Тип | Описание |
|------|-----|----------|
| id | BIGSERIAL PK | ID правила |
| organization_id | BIGINT NOT NULL | Организация (multi-tenant) |
| name | VARCHAR(100) | Название правила |
| enabled | BOOLEAN | Активно ли правило |
| event_type | VARCHAR(50) | Тип события (geozone_enter, speed_violation, ...) |
| conditions | JSONB | Допусловия (speedThreshold, sensorType, ...) |
| apply_to_all | BOOLEAN | Для всех ТС организации |
| vehicle_ids | BIGINT[] | Список конкретных ТС |
| group_ids | BIGINT[] | Группы ТС |
| geozone_ids | BIGINT[] | Фильтр по геозонам |
| channels | VARCHAR(20)[] | Каналы (email, sms, push, telegram, webhook) |
| recipients | JSONB | {userIds, emails, phones, telegramChatIds, webhookUrls} |
| throttle_minutes | INTEGER | Интервал throttle (по умолч. 15 мин) |
| template_id | BIGINT FK | Ссылка на шаблон |
| custom_message | TEXT | Кастомное сообщение (если нет шаблона) |
| schedule | JSONB | Расписание {workingHoursOnly, startHour, endHour, ...} |
| created_at | TIMESTAMPTZ | Дата создания |
| updated_at | TIMESTAMPTZ | Дата обновления |

### Таблица: `notification_templates`

Шаблоны сообщений с подстановкой переменных `{{variable}}`.

| Поле | Тип | Описание |
|------|-----|----------|
| id | BIGSERIAL PK | ID шаблона |
| organization_id | BIGINT NULL | NULL = системный шаблон |
| name | VARCHAR(100) | Название |
| event_type | VARCHAR(50) | Тип события |
| email_subject | VARCHAR(200) | Тема email |
| email_body | TEXT | Тело email |
| sms_body | VARCHAR(160) | SMS (до 160 символов) |
| push_title | VARCHAR(100) | Заголовок push |
| push_body | VARCHAR(200) | Тело push |
| telegram_body | TEXT | Telegram (HTML-разметка) |
| created_at | TIMESTAMPTZ | Дата создания |

### Таблица: `notification_history`

История отправленных уведомлений (TimescaleDB hypertable).

| Поле | Тип | Описание |
|------|-----|----------|
| id | BIGSERIAL | ID записи |
| rule_id | BIGINT FK | Правило |
| organization_id | BIGINT NOT NULL | Организация |
| event_type | VARCHAR(50) | Тип события |
| vehicle_id | BIGINT | ТС |
| channel | VARCHAR(20) | Канал доставки |
| recipient | VARCHAR(200) | Получатель |
| status | VARCHAR(20) | sent / delivered / failed / throttled |
| message | TEXT | Текст сообщения |
| error_message | TEXT | Ошибка (если failed) |
| created_at | TIMESTAMPTZ | Время создания |

## Redis ключи

| Ключ | Тип | TTL | Описание |
|------|-----|-----|----------|
| `throttle:{ruleId}:{vehicleId}` | STRING | = throttle_minutes правила | Метка последней отправки |
| `rate:{userId}:{channel}:{hour}` | COUNTER | 1 час | Счётчик rate limit пользователя |
| `suppress:{orgId}:{eventHash}` | STRING | 5 мин | Подавление спама (>10 дублей) |
| `rules_cache:{orgId}:{eventType}` | STRING (JSON) | 5 мин | Кеш правил для ускорения matcher |

### Rate limits по каналам

| Канал | Лимит в час |
|-------|------------|
| email | 100 |
| sms | 20 |
| push | 50 |
| telegram | 30 |
| webhook | 200 |
