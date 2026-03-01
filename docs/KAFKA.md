# Notification Service — Kafka интеграция

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

## Потребляемые топики

| Топик | Consumer Group | Событие |
|-------|---------------|---------|
| `geozone-events` | `notification-service` | Въезд/выезд из геозоны |
| `rule-violations` | `notification-service` | Превышение скорости |
| `device-status` | `notification-service` | Online/Offline устройства |

## Формат входных сообщений

### geozone-events

```json
{
  "eventType": "enter",
  "organizationId": 1,
  "vehicleId": 42,
  "geozoneId": 5,
  "geozoneName": "Склад №1",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "speed": 30.5,
  "timestamp": "2026-03-01T10:00:00Z"
}
```

### rule-violations

```json
{
  "violationType": "speed_limit_exceeded",
  "organizationId": 1,
  "vehicleId": 42,
  "speed": 120,
  "speedLimit": 60,
  "latitude": 55.7558,
  "longitude": 37.6173,
  "ruleId": 7,
  "ruleName": "Городская зона 60 км/ч",
  "timestamp": "2026-03-01T10:00:00Z"
}
```

### device-status

```json
{
  "statusType": "offline",
  "organizationId": 1,
  "deviceId": 100,
  "vehicleId": 42,
  "imei": "352625060000000",
  "timestamp": "2026-03-01T10:00:00Z"
}
```

## Заметки

- Consumer group единый — `notification-service`.
- Партицирование по `vehicleId` — порядок событий для одного ТС гарантирован.
- При ошибке обработки — лог + продолжение (не останавливать consumer).
- Полный список топиков → [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md).
