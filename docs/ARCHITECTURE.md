# Notification Service — Архитектура

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

## Общая схема

```mermaid
flowchart TB
    subgraph Kafka["Apache Kafka"]
        GE[geozone-events]
        RV[rule-violations]
        DS[device-status]
    end

    subgraph NS["Notification Service :8094"]
        Consumer[Event Consumer]

        subgraph Processing["Обработка"]
            Matcher[Rule Matcher]
            Throttle[Throttle / Dedup]
            Template[Template Engine]
        end

        subgraph Channels["Каналы доставки"]
            Email[Email Sender]
            SMS[SMS Sender]
            Push[Push / FCM]
            TG[Telegram Bot]
            WH[Webhook Caller]
        end

        REST[REST API]
    end

    subgraph External["Внешние API"]
        SMTP[SMTP Server]
        SMSGW[SMS Gateway]
        FCM[Firebase FCM]
        TGBot[Telegram API]
        WHUrl[Webhook URLs]
    end

    subgraph Storage["Хранилища"]
        PG[(PostgreSQL)]
        Redis[(Redis)]
    end

    GE & RV & DS --> Consumer
    Consumer --> Matcher
    Matcher --> Throttle
    Throttle --> Template
    Template --> Email & SMS & Push & TG & WH

    Email --> SMTP
    SMS --> SMSGW
    Push --> FCM
    TG --> TGBot
    WH --> WHUrl

    Matcher <--> PG
    Throttle <--> Redis
    REST <--> PG
```

## Sequence диаграмма

```mermaid
sequenceDiagram
    participant K as Kafka
    participant C as Consumer
    participant M as Rule Matcher
    participant PG as PostgreSQL
    participant T as Throttle
    participant R as Redis
    participant TE as Template Engine
    participant CH as Channel
    participant EXT as External API

    K->>C: GeozoneEvent (vehicle=123, zone=5, ENTER)
    C->>M: matchRules(event)
    M->>PG: SELECT rules WHERE event_type='geozone_enter'<br/>AND (apply_to_all OR vehicle_ids @> {123})
    PG-->>M: [rule_1, rule_7]

    loop Для каждого правила
        M->>T: shouldSend(ruleId, vehicleId)
        T->>R: GET throttle:{rule_id}:{vehicle_id}
        R-->>T: (не найден)
        T-->>M: true

        M->>TE: render(templateId, eventData)
        TE-->>M: RenderedMessage

        M->>CH: send(message, recipients)
        CH->>EXT: HTTP / SMTP / etc.
        EXT-->>CH: OK

        M->>T: setThrottle(ruleId, vehicleId)
        T->>R: SETEX throttle:1:123 900 "1"

        M->>PG: INSERT INTO notification_history(...)
    end
```

## Компоненты

| Компонент | Ответственность |
|-----------|----------------|
| **EventConsumer** | Kafka consumer (multi-topic), десериализация |
| **RuleMatcher** | Поиск правил в PostgreSQL, фильтрация по vehicle/group |
| **ThrottleService** | Rate limiting + дедупликация через Redis |
| **TemplateEngine** | Рендеринг шаблонов с подстановкой переменных |
| **EmailChannel** | Отправка email через SMTP |
| **SmsChannel** | Отправка SMS через SMS.ru / Twilio |
| **PushChannel** | Push через Firebase Cloud Messaging |
| **TelegramChannel** | Сообщения через Telegram Bot API |
| **WebhookChannel** | POST на URL с retry + exponential backoff |
| **NotificationService** | Оркестратор: matcher → throttle → template → channels |
| **REST API** | CRUD правил, шаблонов, история, тест отправки |

## ZIO Layer граф

```
AppConfig.allLayers
  ├── TransactorLayer.live (PostgreSQL)
  ├── RuleRepository.live
  ├── TemplateRepository.live
  ├── HistoryRepository.live
  ├── ThrottleService.live (Redis)
  ├── TemplateEngine.live
  ├── EmailChannel.live
  ├── SmsChannel.live
  ├── PushChannel.live
  ├── TelegramChannel.live
  ├── WebhookChannel.live
  ├── DeliveryService.live (все каналы)
  ├── RuleMatcher.live
  ├── NotificationService.live
  ├── EventConsumer.live
  └── Server.live (zio-http)
```
