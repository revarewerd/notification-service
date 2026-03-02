package com.wayrecall.tracker.notifications.kafka

import zio.*
import zio.stream.*
import zio.kafka.consumer.*
import zio.kafka.serde.Serde
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.KafkaConfig
import com.wayrecall.tracker.notifications.service.NotificationOrchestrator

// ============================================================
// EVENT CONSUMER — Kafka consumer для 3 топиков:
//   - geozone-events
//   - rule-violations
//   - device-status
//
// Consumer group: notification-service
// Парсинг JSON → NotificationEvent → NotificationOrchestrator
// ============================================================

object EventConsumer:

  /** Запустить Kafka consumer, который слушает все настроенные топики */
  def run: ZIO[KafkaConfig & NotificationOrchestrator & Consumer, Throwable, Unit] =
    for {
      config      <- ZIO.service[KafkaConfig]
      orchestrator <- ZIO.service[NotificationOrchestrator]
      _           <- ZIO.logInfo(s"Запуск Kafka consumer на топики: ${config.topics.mkString(", ")}")
      // Подписываемся на все настроенные топики
      _           <- Consumer
                       .plainStream(Subscription.topics(config.topics.head, config.topics.tail*), Serde.string, Serde.string)
                       .mapZIO { record =>
                         val topic = record.record.topic()
                         processRecord(record.value, topic, orchestrator)
                           .catchAll(err =>
                             ZIO.logError(s"Ошибка обработки события из $topic: $err")
                           )
                           .as(record.offset)
                       }
                       .aggregateAsync(Consumer.offsetBatches)
                       .mapZIO(_.commit)
                       .runDrain
    } yield ()

  /** Парсинг и обработка одной записи из Kafka */
  private def processRecord(
    value: String,
    topic: String,
    orchestrator: NotificationOrchestrator
  ): IO[NotificationError, Unit] =
    for {
      event <- parseEvent(value, topic)
      _     <- orchestrator.processEvent(event).unit
    } yield ()

  /** Парсинг JSON в NotificationEvent в зависимости от топика */
  private def parseEvent(json: String, topic: String): IO[NotificationError, NotificationEvent] =
    topic match
      case t if t.contains("geozone-events")   => parseGeozoneEvent(json)
      case t if t.contains("rule-violations")   => parseRuleViolation(json)
      case t if t.contains("device-status")     => parseDeviceStatus(json)
      case _                                     => ZIO.fail(EventParseError(s"Неизвестный топик: $topic"))

  /** Парсинг geozone-events → NotificationEvent */
  private def parseGeozoneEvent(json: String): IO[NotificationError, NotificationEvent] =
    // Ожидаемый формат: { "organizationId": 1, "vehicleId": 100, "geozoneId": 5, "geozoneName": "Склад", "action": "enter"|"leave", ... }
    json.fromJson[Map[String, zio.json.ast.Json]] match
      case Left(err) => ZIO.fail(EventParseError(s"Невалидный JSON geozone-event: $err"))
      case Right(map) =>
        val orgId = extractLong(map, "organizationId")
        val vehId = extractLong(map, "vehicleId")
        val action = extractString(map, "action").getOrElse("enter")
        val eventType = if action == "leave" then EventType.GeozoneLeave else EventType.GeozoneEnter
        (orgId, vehId) match
          case (Some(o), Some(v)) =>
            val payload = map.map { case (k, v) => k -> v.toString.stripPrefix("\"").stripSuffix("\"") }
            ZIO.succeed(NotificationEvent(
              eventType = eventType,
              organizationId = OrganizationId(o),
              vehicleId = VehicleId(v),
              vehicleName = None,
              timestamp = java.time.Instant.now(),
              payload = payload,
              latitude = None,
              longitude = None
            ))
          case _ => ZIO.fail(EventParseError("geozone-event: отсутствует organizationId или vehicleId"))

  /** Парсинг rule-violations → NotificationEvent */
  private def parseRuleViolation(json: String): IO[NotificationError, NotificationEvent] =
    json.fromJson[Map[String, zio.json.ast.Json]] match
      case Left(err) => ZIO.fail(EventParseError(s"Невалидный JSON rule-violation: $err"))
      case Right(map) =>
        val orgId = extractLong(map, "organizationId")
        val vehId = extractLong(map, "vehicleId")
        val violationType = extractString(map, "violationType").getOrElse("speed_violation")
        val eventType = violationType match
          case "speed"          => EventType.SpeedViolation
          case "geozone_speed"  => EventType.GeozoneSpeedViolation
          case _                => EventType.SpeedViolation
        (orgId, vehId) match
          case (Some(o), Some(v)) =>
            val payload = map.map { case (k, v) => k -> v.toString.stripPrefix("\"").stripSuffix("\"") }
            ZIO.succeed(NotificationEvent(
              eventType = eventType,
              organizationId = OrganizationId(o),
              vehicleId = VehicleId(v),
              vehicleName = None,
              timestamp = java.time.Instant.now(),
              payload = payload,
              latitude = None,
              longitude = None
            ))
          case _ => ZIO.fail(EventParseError("rule-violation: отсутствует organizationId или vehicleId"))

  /** Парсинг device-status → NotificationEvent */
  private def parseDeviceStatus(json: String): IO[NotificationError, NotificationEvent] =
    json.fromJson[Map[String, zio.json.ast.Json]] match
      case Left(err) => ZIO.fail(EventParseError(s"Невалидный JSON device-status: $err"))
      case Right(map) =>
        val orgId = extractLong(map, "organizationId")
        val vehId = extractLong(map, "vehicleId")
        val status = extractString(map, "status").getOrElse("offline")
        val eventType = status match
          case "online"  => EventType.DeviceOnline
          case "offline" => EventType.DeviceOffline
          case _         => EventType.DeviceOffline
        (orgId, vehId) match
          case (Some(o), Some(v)) =>
            val payload = map.map { case (k, v) => k -> v.toString.stripPrefix("\"").stripSuffix("\"") }
            ZIO.succeed(NotificationEvent(
              eventType = eventType,
              organizationId = OrganizationId(o),
              vehicleId = VehicleId(v),
              vehicleName = None,
              timestamp = java.time.Instant.now(),
              payload = payload,
              latitude = None,
              longitude = None
            ))
          case _ => ZIO.fail(EventParseError("device-status: отсутствует organizationId или vehicleId"))

  // === Вспомогательные функции для парсинга JSON ===

  private def extractLong(map: Map[String, zio.json.ast.Json], key: String): Option[Long] =
    map.get(key).flatMap { v =>
      v.toString.stripPrefix("\"").stripSuffix("\"").toLongOption
    }

  private def extractString(map: Map[String, zio.json.ast.Json], key: String): Option[String] =
    map.get(key).map(_.toString.stripPrefix("\"").stripSuffix("\""))
