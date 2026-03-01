package com.wayrecall.tracker.notifications.domain

import zio.json.*
import java.time.Instant

// ============================================================
// DOMAIN ENTITIES — Notification Service
// ============================================================

// ---- Opaque Types (типобезопасные идентификаторы) ----

opaque type OrganizationId = Long
object OrganizationId:
  def apply(value: Long): OrganizationId = value
  extension (id: OrganizationId) def value: Long = id
  given JsonCodec[OrganizationId] = JsonCodec.long.transform(OrganizationId(_), _.value)

opaque type VehicleId = Long
object VehicleId:
  def apply(value: Long): VehicleId = value
  extension (id: VehicleId) def value: Long = id
  given JsonCodec[VehicleId] = JsonCodec.long.transform(VehicleId(_), _.value)

opaque type RuleId = Long
object RuleId:
  def apply(value: Long): RuleId = value
  extension (id: RuleId) def value: Long = id
  given JsonCodec[RuleId] = JsonCodec.long.transform(RuleId(_), _.value)

opaque type TemplateId = Long
object TemplateId:
  def apply(value: Long): TemplateId = value
  extension (id: TemplateId) def value: Long = id
  given JsonCodec[TemplateId] = JsonCodec.long.transform(TemplateId(_), _.value)

opaque type UserId = Long
object UserId:
  def apply(value: Long): UserId = value
  extension (id: UserId) def value: Long = id
  given JsonCodec[UserId] = JsonCodec.long.transform(UserId(_), _.value)

// ---- Типы событий ----

enum EventType derives JsonCodec:
  case GeozoneEnter, GeozoneLeave
  case SpeedViolation
  case GeozoneSpeedViolation
  case DeviceOffline, DeviceOnline
  case SensorAlert
  case FuelDrain, FuelRefuel
  case MaintenanceDue
  case CommandResult

// ---- Каналы доставки ----

enum Channel derives JsonCodec:
  case Email, Sms, Push, Telegram, Webhook

// ---- Статус доставки ----

enum DeliveryStatus derives JsonCodec:
  case Sent, Delivered, Failed, RateLimited, Throttled

// ---- Входящее событие из Kafka (унифицированный формат) ----

case class NotificationEvent(
  eventType: EventType,
  organizationId: OrganizationId,
  vehicleId: VehicleId,
  vehicleName: Option[String],
  timestamp: Instant,
  // Специфичные данные (зависят от типа события)
  payload: Map[String, String],
  // Координаты (опционально)
  latitude: Option[Double],
  longitude: Option[Double]
) derives JsonCodec

// ---- Правило уведомления ----

case class NotificationRule(
  id: RuleId,
  organizationId: OrganizationId,
  name: String,
  enabled: Boolean,
  eventType: EventType,
  conditions: RuleConditions,
  applyToAll: Boolean,
  vehicleIds: List[Long],
  groupIds: List[Long],
  geozoneIds: List[Long],
  channels: List[Channel],
  recipients: Recipients,
  throttleMinutes: Int,
  templateId: Option[TemplateId],
  customMessage: Option[String],
  schedule: Option[Schedule],
  createdAt: Instant,
  updatedAt: Instant
) derives JsonCodec

// ---- Условия правила ----

case class RuleConditions(
  speedThreshold: Option[Int],
  sensorType: Option[String],
  sensorThreshold: Option[Double],
  sensorOperator: Option[String],    // ">", "<", "="
  geozoneAction: Option[String]      // "enter", "leave", "both"
) derives JsonCodec

// ---- Получатели ----

case class Recipients(
  userIds: List[Long],
  emails: List[String],
  phones: List[String],
  telegramChatIds: List[String],
  webhookUrls: List[String]
) derives JsonCodec

object Recipients:
  val empty: Recipients = Recipients(Nil, Nil, Nil, Nil, Nil)

// ---- Расписание ----

case class Schedule(
  timezone: String,
  workingHoursOnly: Boolean,
  startHour: Int,
  endHour: Int,
  workingDays: List[Int]   // 1=Mon, 7=Sun
) derives JsonCodec

// ---- DTO для создания/обновления правила ----

case class CreateRule(
  name: String,
  eventType: EventType,
  conditions: Option[RuleConditions],
  applyToAll: Boolean,
  vehicleIds: Option[List[Long]],
  groupIds: Option[List[Long]],
  geozoneIds: Option[List[Long]],
  channels: List[Channel],
  recipients: Recipients,
  throttleMinutes: Option[Int],
  templateId: Option[TemplateId],
  customMessage: Option[String],
  schedule: Option[Schedule]
) derives JsonCodec

// ---- Шаблон уведомления ----

case class NotificationTemplate(
  id: TemplateId,
  organizationId: Option[OrganizationId],   // NULL = системный
  name: String,
  eventType: EventType,
  emailSubject: Option[String],
  emailBody: Option[String],
  smsBody: Option[String],
  pushTitle: Option[String],
  pushBody: Option[String],
  telegramBody: Option[String],
  createdAt: Instant
) derives JsonCodec

case class CreateTemplate(
  name: String,
  eventType: EventType,
  emailSubject: Option[String],
  emailBody: Option[String],
  smsBody: Option[String],
  pushTitle: Option[String],
  pushBody: Option[String],
  telegramBody: Option[String]
) derives JsonCodec

// ---- Запись истории уведомлений ----

case class NotificationHistoryEntry(
  id: Long,
  ruleId: Option[RuleId],
  organizationId: OrganizationId,
  eventType: EventType,
  vehicleId: VehicleId,
  channel: Channel,
  recipient: String,
  status: DeliveryStatus,
  message: String,
  errorMessage: Option[String],
  createdAt: Instant
) derives JsonCodec

// ---- Результат доставки ----

case class DeliveryResult(
  channel: Channel,
  status: DeliveryStatus,
  messageId: Option[String],
  error: Option[String]
)

object DeliveryResult:
  def success(channel: Channel, messageId: String = ""): DeliveryResult =
    DeliveryResult(channel, DeliveryStatus.Sent, Some(messageId), None)

  def failed(channel: Channel, error: String): DeliveryResult =
    DeliveryResult(channel, DeliveryStatus.Failed, None, Some(error))

  def rateLimited(channel: Channel): DeliveryResult =
    DeliveryResult(channel, DeliveryStatus.RateLimited, None, Some("Rate limit exceeded"))

  def throttled(channel: Channel): DeliveryResult =
    DeliveryResult(channel, DeliveryStatus.Throttled, None, Some("Throttled"))

// ---- Отрендеренное сообщение ----

case class RenderedMessage(
  subject: Option[String],    // Для email
  body: String,               // Полный текст
  shortBody: String           // Для SMS (до 160 символов)
)

// ---- Запрос тестовой отправки ----

case class TestNotificationRequest(
  channel: Channel,
  recipient: String,
  message: String
) derives JsonCodec
