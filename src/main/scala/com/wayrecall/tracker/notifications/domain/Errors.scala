package com.wayrecall.tracker.notifications.domain

// ============================================================
// DOMAIN ERRORS — Notification Service
// ============================================================

/**
 * Типизированные ошибки сервиса уведомлений.
 * Sealed trait для exhaustive pattern matching.
 */
sealed trait NotificationError:
  def message: String

object NotificationError:

  // ---- Правила ----
  case class RuleNotFound(id: Long) extends NotificationError:
    def message = s"Правило не найдено: id=$id"

  case class InvalidRule(reason: String) extends NotificationError:
    def message = s"Невалидное правило: $reason"

  // ---- Шаблоны ----
  case class TemplateNotFound(id: Long) extends NotificationError:
    def message = s"Шаблон не найден: id=$id"

  case class TemplateRenderError(reason: String) extends NotificationError:
    def message = s"Ошибка рендеринга шаблона: $reason"

  // ---- Доставка ----
  case class DeliveryError(channel: String, reason: String) extends NotificationError:
    def message = s"Ошибка доставки [$channel]: $reason"

  case class ChannelDisabled(channel: String) extends NotificationError:
    def message = s"Канал отключён: $channel"

  // ---- Инфраструктура ----
  case class DatabaseError(cause: Throwable) extends NotificationError:
    def message = s"Ошибка БД: ${cause.getMessage}"

  case class RedisError(cause: Throwable) extends NotificationError:
    def message = s"Ошибка Redis: ${cause.getMessage}"

  case class KafkaError(cause: Throwable) extends NotificationError:
    def message = s"Ошибка Kafka: ${cause.getMessage}"

  case class EventParseError(reason: String) extends NotificationError:
    def message = s"Ошибка разбора события: $reason"

  // ---- Multi-tenant ----
  case object MissingOrganizationId extends NotificationError:
    def message = "Заголовок X-Organization-Id обязателен"

  case class OrganizationMismatch(expected: Long, actual: Long) extends NotificationError:
    def message = s"Несоответствие организации: ожидалась $expected, получена $actual"
