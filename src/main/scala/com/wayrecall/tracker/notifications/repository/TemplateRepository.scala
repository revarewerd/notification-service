package com.wayrecall.tracker.notifications.repository

import zio.*
import zio.interop.catz.*
import doobie.*
import doobie.implicits.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import java.time.Instant

// ============================================================
// TEMPLATE REPOSITORY — CRUD шаблонов уведомлений
// ============================================================

trait TemplateRepository:
  /** Все шаблоны (системные + организации) */
  def findAll(orgId: OrganizationId): IO[NotificationError, List[NotificationTemplate]]

  /** Шаблон по ID */
  def findById(id: TemplateId): IO[NotificationError, Option[NotificationTemplate]]

  /** Шаблон по типу события (сначала организации, потом системный) */
  def findByEventType(orgId: OrganizationId, eventType: String): IO[NotificationError, Option[NotificationTemplate]]

  /** Создать шаблон */
  def create(orgId: OrganizationId, req: CreateTemplate): IO[NotificationError, NotificationTemplate]

  /** Обновить шаблон */
  def update(id: TemplateId, orgId: OrganizationId, req: CreateTemplate): IO[NotificationError, Option[NotificationTemplate]]

object TemplateRepository:

  val live: ZLayer[Transactor[Task], Nothing, TemplateRepository] =
    ZLayer.fromFunction((xa: Transactor[Task]) => Live(xa))

  private case class Live(xa: Transactor[Task]) extends TemplateRepository:

    override def findAll(orgId: OrganizationId): IO[NotificationError, List[NotificationTemplate]] =
      sql"""SELECT id, organization_id, name, event_type,
                   email_subject, email_body, sms_body,
                   push_title, push_body, telegram_body, created_at
            FROM notification_templates
            WHERE organization_id = ${orgId.value} OR organization_id IS NULL
            ORDER BY organization_id DESC NULLS LAST, name"""
        .query[(Long, Option[Long], String, String,
                Option[String], Option[String], Option[String],
                Option[String], Option[String], Option[String], Instant)]
        .map { case (id, oid, name, evType, es, eb, sb, pt, pb, tb, ca) =>
          import zio.json.*
          NotificationTemplate(
            id = TemplateId(id),
            organizationId = oid.map(OrganizationId(_)),
            name = name,
            eventType = evType.fromJson[EventType].getOrElse(EventType.GeozoneEnter),
            emailSubject = es, emailBody = eb, smsBody = sb,
            pushTitle = pt, pushBody = pb, telegramBody = tb,
            createdAt = ca
          )
        }
        .to[List]
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def findById(id: TemplateId): IO[NotificationError, Option[NotificationTemplate]] =
      sql"""SELECT id, organization_id, name, event_type,
                   email_subject, email_body, sms_body,
                   push_title, push_body, telegram_body, created_at
            FROM notification_templates WHERE id = ${id.value}"""
        .query[(Long, Option[Long], String, String,
                Option[String], Option[String], Option[String],
                Option[String], Option[String], Option[String], Instant)]
        .map { case (id, oid, name, evType, es, eb, sb, pt, pb, tb, ca) =>
          import zio.json.*
          NotificationTemplate(
            id = TemplateId(id),
            organizationId = oid.map(OrganizationId(_)),
            name = name,
            eventType = evType.fromJson[EventType].getOrElse(EventType.GeozoneEnter),
            emailSubject = es, emailBody = eb, smsBody = sb,
            pushTitle = pt, pushBody = pb, telegramBody = tb,
            createdAt = ca
          )
        }
        .option
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def findByEventType(orgId: OrganizationId, eventType: String): IO[NotificationError, Option[NotificationTemplate]] =
      // Сначала ищем кастомный шаблон организации, потом системный
      sql"""SELECT id, organization_id, name, event_type,
                   email_subject, email_body, sms_body,
                   push_title, push_body, telegram_body, created_at
            FROM notification_templates
            WHERE event_type = $eventType
              AND (organization_id = ${orgId.value} OR organization_id IS NULL)
            ORDER BY organization_id DESC NULLS LAST
            LIMIT 1"""
        .query[(Long, Option[Long], String, String,
                Option[String], Option[String], Option[String],
                Option[String], Option[String], Option[String], Instant)]
        .map { case (id, oid, name, evType, es, eb, sb, pt, pb, tb, ca) =>
          import zio.json.*
          NotificationTemplate(
            id = TemplateId(id),
            organizationId = oid.map(OrganizationId(_)),
            name = name,
            eventType = evType.fromJson[EventType].getOrElse(EventType.GeozoneEnter),
            emailSubject = es, emailBody = eb, smsBody = sb,
            pushTitle = pt, pushBody = pb, telegramBody = tb,
            createdAt = ca
          )
        }
        .option
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def create(orgId: OrganizationId, req: CreateTemplate): IO[NotificationError, NotificationTemplate] =
      import zio.json.*
      val evTypeStr = req.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      sql"""INSERT INTO notification_templates
              (organization_id, name, event_type, email_subject, email_body,
               sms_body, push_title, push_body, telegram_body)
            VALUES (${orgId.value}, ${req.name}, $evTypeStr,
                    ${req.emailSubject}, ${req.emailBody}, ${req.smsBody},
                    ${req.pushTitle}, ${req.pushBody}, ${req.telegramBody})
            RETURNING id"""
        .query[Long]
        .unique
        .transact(xa)
        .mapError(e => DatabaseError(e))
        .flatMap(id => findById(TemplateId(id)).map(_.get))

    override def update(id: TemplateId, orgId: OrganizationId, req: CreateTemplate): IO[NotificationError, Option[NotificationTemplate]] =
      import zio.json.*
      val evTypeStr = req.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      sql"""UPDATE notification_templates SET
              name = ${req.name}, event_type = $evTypeStr,
              email_subject = ${req.emailSubject}, email_body = ${req.emailBody},
              sms_body = ${req.smsBody}, push_title = ${req.pushTitle},
              push_body = ${req.pushBody}, telegram_body = ${req.telegramBody}
            WHERE id = ${id.value} AND organization_id = ${orgId.value}"""
        .update.run
        .transact(xa)
        .mapError(e => DatabaseError(e))
        .flatMap(n => if n > 0 then findById(id) else ZIO.succeed(None))
