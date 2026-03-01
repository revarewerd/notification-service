package com.wayrecall.tracker.notifications.repository

import zio.*
import zio.interop.catz.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import java.time.Instant

// ============================================================
// RULE REPOSITORY — CRUD правил уведомлений
// ============================================================

trait RuleRepository:
  /** Все активные правила организации */
  def findByOrganization(orgId: OrganizationId): IO[NotificationError, List[NotificationRule]]

  /** Поиск правил по типу события и организации (для matcher) */
  def findMatchingRules(orgId: OrganizationId, eventType: String, vehicleId: Long): IO[NotificationError, List[NotificationRule]]

  /** Получить правило по ID */
  def findById(id: RuleId, orgId: OrganizationId): IO[NotificationError, Option[NotificationRule]]

  /** Создать правило */
  def create(orgId: OrganizationId, req: CreateRule): IO[NotificationError, NotificationRule]

  /** Обновить правило */
  def update(id: RuleId, orgId: OrganizationId, req: CreateRule): IO[NotificationError, Option[NotificationRule]]

  /** Удалить правило */
  def delete(id: RuleId, orgId: OrganizationId): IO[NotificationError, Boolean]

  /** Включить/выключить правило */
  def toggle(id: RuleId, orgId: OrganizationId): IO[NotificationError, Option[NotificationRule]]

object RuleRepository:

  val live: ZLayer[Transactor[Task], Nothing, RuleRepository] =
    ZLayer.fromFunction((xa: Transactor[Task]) => Live(xa))

  private case class Live(xa: Transactor[Task]) extends RuleRepository:

    // Маппинг строки БД в доменную модель
    private def mapRow(
      id: Long, orgId: Long, name: String, enabled: Boolean,
      eventType: String, conditions: String, applyToAll: Boolean,
      vehicleIds: List[Long], groupIds: List[Long], geozoneIds: List[Long],
      channels: List[String], recipients: String,
      throttleMinutes: Int, templateId: Option[Long],
      customMessage: Option[String], schedule: Option[String],
      createdAt: Instant, updatedAt: Instant
    ): NotificationRule =
      import zio.json.*
      NotificationRule(
        id = RuleId(id),
        organizationId = OrganizationId(orgId),
        name = name,
        enabled = enabled,
        eventType = eventType.fromJson[EventType].getOrElse(EventType.GeozoneEnter),
        conditions = conditions.fromJson[RuleConditions].getOrElse(RuleConditions(None, None, None, None, None)),
        applyToAll = applyToAll,
        vehicleIds = vehicleIds,
        groupIds = groupIds,
        geozoneIds = geozoneIds,
        channels = channels.flatMap(c => c.fromJson[Channel].toOption),
        recipients = recipients.fromJson[Recipients].getOrElse(Recipients.empty),
        throttleMinutes = throttleMinutes,
        templateId = templateId.map(TemplateId(_)),
        customMessage = customMessage,
        schedule = schedule.flatMap(_.fromJson[Schedule].toOption),
        createdAt = createdAt,
        updatedAt = updatedAt
      )

    override def findByOrganization(orgId: OrganizationId): IO[NotificationError, List[NotificationRule]] =
      sql"""SELECT id, organization_id, name, enabled, event_type,
                   conditions::text, apply_to_all, vehicle_ids, group_ids, geozone_ids,
                   channels::text[], recipients::text, throttle_minutes, template_id,
                   custom_message, schedule::text, created_at, updated_at
            FROM notification_rules
            WHERE organization_id = ${orgId.value}
            ORDER BY created_at DESC"""
        .query[(Long, Long, String, Boolean, String, String, Boolean,
                List[Long], List[Long], List[Long], List[String], String,
                Int, Option[Long], Option[String], Option[String], Instant, Instant)]
        .map(t => mapRow.tupled(t))
        .to[List]
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def findMatchingRules(orgId: OrganizationId, eventType: String, vehicleId: Long): IO[NotificationError, List[NotificationRule]] =
      // Ищем все активные правила для данного типа события
      // Фильтрация по vehicle — apply_to_all ИЛИ vehicleId содержится в vehicle_ids
      sql"""SELECT id, organization_id, name, enabled, event_type,
                   conditions::text, apply_to_all, vehicle_ids, group_ids, geozone_ids,
                   channels::text[], recipients::text, throttle_minutes, template_id,
                   custom_message, schedule::text, created_at, updated_at
            FROM notification_rules
            WHERE organization_id = ${orgId.value}
              AND event_type = $eventType
              AND enabled = true
              AND (apply_to_all = true OR $vehicleId = ANY(vehicle_ids))
            ORDER BY id"""
        .query[(Long, Long, String, Boolean, String, String, Boolean,
                List[Long], List[Long], List[Long], List[String], String,
                Int, Option[Long], Option[String], Option[String], Instant, Instant)]
        .map(t => mapRow.tupled(t))
        .to[List]
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def findById(id: RuleId, orgId: OrganizationId): IO[NotificationError, Option[NotificationRule]] =
      sql"""SELECT id, organization_id, name, enabled, event_type,
                   conditions::text, apply_to_all, vehicle_ids, group_ids, geozone_ids,
                   channels::text[], recipients::text, throttle_minutes, template_id,
                   custom_message, schedule::text, created_at, updated_at
            FROM notification_rules
            WHERE id = ${id.value} AND organization_id = ${orgId.value}"""
        .query[(Long, Long, String, Boolean, String, String, Boolean,
                List[Long], List[Long], List[Long], List[String], String,
                Int, Option[Long], Option[String], Option[String], Instant, Instant)]
        .map(t => mapRow.tupled(t))
        .option
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def create(orgId: OrganizationId, req: CreateRule): IO[NotificationError, NotificationRule] =
      import zio.json.*
      val eventTypeStr = req.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      val conditionsJson = req.conditions.map(_.toJson).getOrElse("{}")
      val channelsArr = req.channels.map(c => c.toJson.stripPrefix("\"").stripSuffix("\""))
      val recipientsJson = req.recipients.toJson
      val scheduleJson = req.schedule.map(_.toJson)

      sql"""INSERT INTO notification_rules
              (organization_id, name, event_type, conditions, apply_to_all,
               vehicle_ids, group_ids, geozone_ids, channels, recipients,
               throttle_minutes, template_id, custom_message, schedule)
            VALUES (${orgId.value}, ${req.name}, $eventTypeStr,
                    $conditionsJson::jsonb, ${req.applyToAll},
                    ${req.vehicleIds.getOrElse(Nil)}, ${req.groupIds.getOrElse(Nil)},
                    ${req.geozoneIds.getOrElse(Nil)},
                    $channelsArr, $recipientsJson::jsonb,
                    ${req.throttleMinutes.getOrElse(15)},
                    ${req.templateId.map(_.value)}, ${req.customMessage},
                    $scheduleJson::jsonb)
            RETURNING id"""
        .query[Long]
        .unique
        .transact(xa)
        .mapError(e => DatabaseError(e))
        .flatMap(id => findById(RuleId(id), orgId).map(_.get))

    override def update(id: RuleId, orgId: OrganizationId, req: CreateRule): IO[NotificationError, Option[NotificationRule]] =
      import zio.json.*
      val eventTypeStr = req.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      val conditionsJson = req.conditions.map(_.toJson).getOrElse("{}")
      val channelsArr = req.channels.map(c => c.toJson.stripPrefix("\"").stripSuffix("\""))
      val recipientsJson = req.recipients.toJson

      sql"""UPDATE notification_rules SET
              name = ${req.name}, event_type = $eventTypeStr,
              conditions = $conditionsJson::jsonb, apply_to_all = ${req.applyToAll},
              vehicle_ids = ${req.vehicleIds.getOrElse(Nil)},
              group_ids = ${req.groupIds.getOrElse(Nil)},
              geozone_ids = ${req.geozoneIds.getOrElse(Nil)},
              channels = $channelsArr, recipients = $recipientsJson::jsonb,
              throttle_minutes = ${req.throttleMinutes.getOrElse(15)},
              template_id = ${req.templateId.map(_.value)},
              custom_message = ${req.customMessage}
            WHERE id = ${id.value} AND organization_id = ${orgId.value}"""
        .update.run
        .transact(xa)
        .mapError(e => DatabaseError(e))
        .flatMap(n => if n > 0 then findById(id, orgId) else ZIO.succeed(None))

    override def delete(id: RuleId, orgId: OrganizationId): IO[NotificationError, Boolean] =
      sql"""DELETE FROM notification_rules
            WHERE id = ${id.value} AND organization_id = ${orgId.value}"""
        .update.run
        .transact(xa)
        .mapError(e => DatabaseError(e))
        .map(_ > 0)

    override def toggle(id: RuleId, orgId: OrganizationId): IO[NotificationError, Option[NotificationRule]] =
      sql"""UPDATE notification_rules SET enabled = NOT enabled
            WHERE id = ${id.value} AND organization_id = ${orgId.value}"""
        .update.run
        .transact(xa)
        .mapError(e => DatabaseError(e))
        .flatMap(n => if n > 0 then findById(id, orgId) else ZIO.succeed(None))
