package com.wayrecall.tracker.notifications.domain

import zio.{Schedule as ZSchedule, *}
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import java.time.Instant

// ============================================================
// ТЕСТЫ ДОМЕННЫХ МОДЕЛЕЙ — Notification Service
// ============================================================

object DomainSpec extends ZIOSpecDefault:

  def spec = suite("Domain — Notification Service")(

    // ---- Opaque Types ----
    suite("Opaque Types")(
      test("OrganizationId — создание и получение значения") {
        val id = OrganizationId(42L)
        assertTrue(id.value == 42L)
      },
      test("VehicleId — создание и получение значения") {
        val id = VehicleId(100L)
        assertTrue(id.value == 100L)
      },
      test("RuleId — создание и получение значения") {
        val id = RuleId(7L)
        assertTrue(id.value == 7L)
      },
      test("TemplateId — создание и получение значения") {
        val id = TemplateId(15L)
        assertTrue(id.value == 15L)
      },
      test("UserId — создание и получение значения") {
        val id = UserId(999L)
        assertTrue(id.value == 999L)
      },
      test("OrganizationId — JSON roundtrip") {
        val id = OrganizationId(42L)
        val json = id.toJson
        val decoded = json.fromJson[OrganizationId]
        assertTrue(
          json == "42",
          decoded == Right(OrganizationId(42L))
        )
      }
    ),

    // ---- EventType ----
    suite("EventType")(
      test("все 11 значений существуют") {
        val all = List(
          EventType.GeozoneEnter, EventType.GeozoneLeave,
          EventType.SpeedViolation, EventType.GeozoneSpeedViolation,
          EventType.DeviceOffline, EventType.DeviceOnline,
          EventType.SensorAlert, EventType.FuelDrain, EventType.FuelRefuel,
          EventType.MaintenanceDue, EventType.CommandResult
        )
        assertTrue(all.length == 11)
      },
      test("JSON roundtrip — GeozoneEnter") {
        val et = EventType.GeozoneEnter
        val json = et.toJson
        val decoded = json.fromJson[EventType]
        assertTrue(decoded == Right(EventType.GeozoneEnter))
      },
      test("JSON roundtrip — SpeedViolation") {
        val et = EventType.SpeedViolation
        val json = et.toJson
        val decoded = json.fromJson[EventType]
        assertTrue(decoded == Right(EventType.SpeedViolation))
      }
    ),

    // ---- Channel ----
    suite("Channel")(
      test("все 5 каналов") {
        val all = List(Channel.Email, Channel.Sms, Channel.Push, Channel.Telegram, Channel.Webhook)
        assertTrue(all.length == 5)
      },
      test("JSON roundtrip — Email") {
        val ch = Channel.Email
        val json = ch.toJson
        val decoded = json.fromJson[Channel]
        assertTrue(decoded == Right(Channel.Email))
      }
    ),

    // ---- DeliveryStatus ----
    suite("DeliveryStatus")(
      test("все 5 статусов") {
        val all = List(
          DeliveryStatus.Sent, DeliveryStatus.Delivered,
          DeliveryStatus.Failed, DeliveryStatus.RateLimited,
          DeliveryStatus.Throttled
        )
        assertTrue(all.length == 5)
      }
    ),

    // ---- Recipients ----
    suite("Recipients")(
      test("empty — все списки пустые") {
        val r = Recipients.empty
        assertTrue(
          r.userIds.isEmpty,
          r.emails.isEmpty,
          r.phones.isEmpty,
          r.telegramChatIds.isEmpty,
          r.webhookUrls.isEmpty
        )
      },
      test("JSON roundtrip") {
        val r = Recipients(
          userIds = List(1L, 2L),
          emails = List("a@b.com"),
          phones = List("+79001234567"),
          telegramChatIds = List("123"),
          webhookUrls = List("https://example.com/hook")
        )
        val json = r.toJson
        val decoded = json.fromJson[Recipients]
        assertTrue(decoded == Right(r))
      }
    ),

    // ---- Schedule ----
    suite("Schedule")(
      test("создание расписания — рабочие часы") {
        val s = Schedule(
          timezone = "Europe/Moscow",
          workingHoursOnly = true,
          startHour = 9,
          endHour = 18,
          workingDays = List(1, 2, 3, 4, 5)
        )
        assertTrue(
          s.timezone == "Europe/Moscow",
          s.workingHoursOnly,
          s.startHour == 9,
          s.endHour == 18,
          s.workingDays.length == 5
        )
      },
      test("JSON roundtrip") {
        val s = Schedule("UTC", false, 0, 24, List(1, 2, 3, 4, 5, 6, 7))
        val json = s.toJson
        val decoded = json.fromJson[Schedule]
        assertTrue(decoded == Right(s))
      }
    ),

    // ---- RuleConditions ----
    suite("RuleConditions")(
      test("полностью пустые условия") {
        val rc = RuleConditions(None, None, None, None, None)
        assertTrue(
          rc.speedThreshold.isEmpty,
          rc.sensorType.isEmpty,
          rc.sensorThreshold.isEmpty,
          rc.sensorOperator.isEmpty,
          rc.geozoneAction.isEmpty
        )
      },
      test("условие скорости") {
        val rc = RuleConditions(Some(90), None, None, None, None)
        assertTrue(rc.speedThreshold.contains(90))
      },
      test("условие датчика") {
        val rc = RuleConditions(None, Some("fuel_level"), Some(10.0), Some("<"), None)
        assertTrue(
          rc.sensorType.contains("fuel_level"),
          rc.sensorThreshold.contains(10.0),
          rc.sensorOperator.contains("<")
        )
      }
    ),

    // ---- DeliveryResult factory methods ----
    suite("DeliveryResult")(
      test("success — статус Sent, нет ошибки") {
        val r = DeliveryResult.success(Channel.Email, "a@b.com", "msg-123")
        assertTrue(
          r.channel == Channel.Email,
          r.recipient == "a@b.com",
          r.status == DeliveryStatus.Sent,
          r.messageId.contains("msg-123"),
          r.error.isEmpty
        )
      },
      test("success — пустой messageId по умолчанию") {
        val r = DeliveryResult.success(Channel.Sms, "+79001234567")
        assertTrue(
          r.status == DeliveryStatus.Sent,
          r.messageId.contains("")
        )
      },
      test("failed — статус Failed, есть ошибка") {
        val r = DeliveryResult.failed(Channel.Push, "user-1", "Connection refused")
        assertTrue(
          r.status == DeliveryStatus.Failed,
          r.error.contains("Connection refused"),
          r.messageId.isEmpty
        )
      },
      test("rateLimited — статус RateLimited") {
        val r = DeliveryResult.rateLimited(Channel.Telegram, "chat-42")
        assertTrue(
          r.status == DeliveryStatus.RateLimited,
          r.error.contains("Rate limit exceeded")
        )
      },
      test("throttled — статус Throttled") {
        val r = DeliveryResult.throttled(Channel.Webhook, "https://hook.io")
        assertTrue(
          r.status == DeliveryStatus.Throttled,
          r.error.contains("Throttled")
        )
      }
    ),

    // ---- RenderedMessage ----
    suite("RenderedMessage")(
      test("создание с subject") {
        val rm = RenderedMessage(Some("Subject"), "Full body text", "Short body")
        assertTrue(
          rm.subject.contains("Subject"),
          rm.body == "Full body text",
          rm.shortBody == "Short body"
        )
      },
      test("создание без subject (SMS)") {
        val rm = RenderedMessage(None, "Body for SMS", "Body for SMS")
        assertTrue(rm.subject.isEmpty)
      }
    ),

    // ---- NotificationEvent ----
    suite("NotificationEvent")(
      test("JSON roundtrip") {
        val now = Instant.now()
        val event = NotificationEvent(
          eventType = EventType.SpeedViolation,
          organizationId = OrganizationId(1L),
          vehicleId = VehicleId(42L),
          vehicleName = Some("КамАЗ А123БВ"),
          timestamp = now,
          payload = Map("speed" -> "120", "speedLimit" -> "90"),
          latitude = Some(55.7558),
          longitude = Some(37.6173)
        )
        val json = event.toJson
        val decoded = json.fromJson[NotificationEvent]
        assertTrue(decoded.isRight)
      },
      test("без координат и имени ТС") {
        val now = Instant.now()
        val event = NotificationEvent(
          eventType = EventType.DeviceOffline,
          organizationId = OrganizationId(1L),
          vehicleId = VehicleId(10L),
          vehicleName = None,
          timestamp = now,
          payload = Map.empty,
          latitude = None,
          longitude = None
        )
        assertTrue(
          event.vehicleName.isEmpty,
          event.latitude.isEmpty,
          event.longitude.isEmpty
        )
      }
    ),

    // ---- NotificationRule ----
    suite("NotificationRule")(
      test("создание с полными полями") {
        val now = Instant.now()
        val rule = NotificationRule(
          id = RuleId(1L),
          organizationId = OrganizationId(10L),
          name = "Превышение скорости",
          enabled = true,
          eventType = EventType.SpeedViolation,
          conditions = RuleConditions(Some(90), None, None, None, None),
          applyToAll = false,
          vehicleIds = List(42L, 43L),
          groupIds = Nil,
          geozoneIds = Nil,
          channels = List(Channel.Email, Channel.Telegram),
          recipients = Recipients(List(1L), List("admin@company.com"), Nil, List("chat-1"), Nil),
          throttleMinutes = 30,
          templateId = Some(TemplateId(5L)),
          customMessage = None,
          schedule = Some(Schedule("Europe/Moscow", true, 9, 18, List(1, 2, 3, 4, 5))),
          createdAt = now,
          updatedAt = now
        )
        assertTrue(
          rule.name == "Превышение скорости",
          rule.enabled,
          rule.channels.length == 2,
          rule.throttleMinutes == 30,
          rule.schedule.isDefined
        )
      }
    )
  )
