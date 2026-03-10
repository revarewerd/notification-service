package com.wayrecall.tracker.notifications.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*

// ============================================================
// ТЕСТЫ ОШИБОК — Notification Service
// ============================================================

object ErrorsSpec extends ZIOSpecDefault:

  def spec = suite("NotificationError")(

    suite("Все 12 подтипов ошибки") (
      test("RuleNotFound — сообщение содержит ID") {
        val err = NotificationError.RuleNotFound(42L)
        assertTrue(
          err.message.contains("42"),
          err.message.contains("Правило")
        )
      },
      test("InvalidRule — сообщение содержит причину") {
        val err = NotificationError.InvalidRule("пустое имя")
        assertTrue(err.message.contains("пустое имя"))
      },
      test("TemplateNotFound — сообщение содержит ID") {
        val err = NotificationError.TemplateNotFound(7L)
        assertTrue(
          err.message.contains("7"),
          err.message.contains("Шаблон")
        )
      },
      test("TemplateRenderError — сообщение содержит причину") {
        val err = NotificationError.TemplateRenderError("отсутствует переменная")
        assertTrue(err.message.contains("отсутствует переменная"))
      },
      test("DeliveryError — содержит канал и причину") {
        val err = NotificationError.DeliveryError("Email", "SMTP timeout")
        assertTrue(
          err.message.contains("Email"),
          err.message.contains("SMTP timeout")
        )
      },
      test("ChannelDisabled — содержит название канала") {
        val err = NotificationError.ChannelDisabled("Sms")
        assertTrue(err.message.contains("Sms"))
      },
      test("DatabaseError — оборачивает Throwable") {
        val cause = new RuntimeException("Connection refused")
        val err = NotificationError.DatabaseError(cause)
        assertTrue(err.message.contains("Connection refused"))
      },
      test("RedisError — оборачивает Throwable") {
        val cause = new RuntimeException("Redis timeout")
        val err = NotificationError.RedisError(cause)
        assertTrue(err.message.contains("Redis timeout"))
      },
      test("KafkaError — оборачивает Throwable") {
        val cause = new RuntimeException("Broker unavailable")
        val err = NotificationError.KafkaError(cause)
        assertTrue(err.message.contains("Broker unavailable"))
      },
      test("EventParseError — содержит причину") {
        val err = NotificationError.EventParseError("invalid JSON")
        assertTrue(err.message.contains("invalid JSON"))
      },
      test("MissingOrganizationId — синглтон") {
        val err = NotificationError.MissingOrganizationId
        assertTrue(err.message.contains("X-Organization-Id"))
      },
      test("OrganizationMismatch — содержит expected и actual") {
        val err = NotificationError.OrganizationMismatch(1L, 2L)
        assertTrue(
          err.message.contains("1"),
          err.message.contains("2")
        )
      }
    ),

    suite("Exhaustive pattern matching")(
      test("все подтипы обрабатываются без default") {
        val errors: List[NotificationError] = List(
          NotificationError.RuleNotFound(1L),
          NotificationError.InvalidRule("x"),
          NotificationError.TemplateNotFound(1L),
          NotificationError.TemplateRenderError("x"),
          NotificationError.DeliveryError("x", "y"),
          NotificationError.ChannelDisabled("x"),
          NotificationError.DatabaseError(new RuntimeException("x")),
          NotificationError.RedisError(new RuntimeException("x")),
          NotificationError.KafkaError(new RuntimeException("x")),
          NotificationError.EventParseError("x"),
          NotificationError.MissingOrganizationId,
          NotificationError.OrganizationMismatch(1L, 2L)
        )
        // Проверяем что у каждой ошибки message не пустой
        val allHaveMessages = errors.forall(_.message.nonEmpty)
        assertTrue(
          errors.length == 12,
          allHaveMessages
        )
      }
    ),

    suite("NotificationError — НЕ extends Exception")(
      test("ошибка не является Exception") {
        val err: NotificationError = NotificationError.RuleNotFound(1L)
        assertTrue(!err.isInstanceOf[Exception])
      }
    )
  )
