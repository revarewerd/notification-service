# Notification Service — REST API

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

Все запросы требуют заголовок `X-Organization-Id`.

## Endpoints

### Health

```
GET /health
```

### Правила уведомлений

```
GET    /api/v1/rules                — Список правил
POST   /api/v1/rules                — Создать правило
GET    /api/v1/rules/{id}           — Получить правило
PUT    /api/v1/rules/{id}           — Обновить правило
DELETE /api/v1/rules/{id}           — Удалить правило
POST   /api/v1/rules/{id}/toggle    — Вкл/выкл правило
```

### Шаблоны

```
GET    /api/v1/templates            — Список шаблонов
POST   /api/v1/templates            — Создать шаблон
PUT    /api/v1/templates/{id}       — Обновить шаблон
```

### История

```
GET    /api/v1/history?vehicle_id=&from=&to=&status=
```

### Тестовая отправка

```
POST   /api/v1/test
```

## Примеры

### Создать правило

```bash
curl -X POST http://localhost:8094/api/v1/rules \
  -H "Content-Type: application/json" \
  -H "X-Organization-Id: 1" \
  -d '{
    "name": "Выезд из склада",
    "eventType": "geozone_leave",
    "applyToAll": true,
    "geozoneIds": [5],
    "channels": ["email", "telegram"],
    "recipients": {
      "emails": ["dispatch@company.com"],
      "telegramChatIds": ["-100123456"]
    },
    "throttleMinutes": 15
  }'
```

### Тестовое уведомление

```bash
curl -X POST http://localhost:8094/api/v1/test \
  -H "Content-Type: application/json" \
  -H "X-Organization-Id: 1" \
  -d '{
    "channel": "email",
    "recipient": "test@example.com",
    "message": "Тестовое уведомление"
  }'
```

### История уведомлений

```bash
curl "http://localhost:8094/api/v1/history?vehicle_id=42&from=2026-03-01T00:00:00Z&to=2026-03-01T23:59:59Z" \
  -H "X-Organization-Id: 1"
```
