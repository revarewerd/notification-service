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
// HISTORY REPOSITORY — История уведомлений
// ============================================================

trait HistoryRepository:
  /** Сохранить запись в историю */
  def save(
    ruleId: Option[RuleId],
    orgId: OrganizationId,
    eventType: String,
    vehicleId: VehicleId,
    channel: Channel,
    recipient: String,
    status: DeliveryStatus,
    message: String,
    errorMessage: Option[String]
  ): IO[NotificationError, Long]

  /** Запросить историю с фильтрами */
  def find(
    orgId: OrganizationId,
    vehicleId: Option[VehicleId],
    ruleId: Option[RuleId],
    from: Option[Instant],
    to: Option[Instant],
    status: Option[String],
    limit: Int
  ): IO[NotificationError, List[NotificationHistoryEntry]]

object HistoryRepository:

  val live: ZLayer[Transactor[Task], Nothing, HistoryRepository] =
    ZLayer.fromFunction((xa: Transactor[Task]) => Live(xa))

  private case class Live(xa: Transactor[Task]) extends HistoryRepository:

    override def save(
      ruleId: Option[RuleId],
      orgId: OrganizationId,
      eventType: String,
      vehicleId: VehicleId,
      channel: Channel,
      recipient: String,
      status: DeliveryStatus,
      message: String,
      errorMessage: Option[String]
    ): IO[NotificationError, Long] =
      import zio.json.*
      val channelStr = channel.toJson.stripPrefix("\"").stripSuffix("\"")
      val statusStr = status.toJson.stripPrefix("\"").stripSuffix("\"")

      sql"""INSERT INTO notification_history
              (rule_id, organization_id, event_type, vehicle_id,
               channel, recipient, status, message, error_message)
            VALUES (${ruleId.map(_.value)}, ${orgId.value}, $eventType,
                    ${vehicleId.value}, $channelStr, $recipient,
                    $statusStr, $message, $errorMessage)
            RETURNING id"""
        .query[Long]
        .unique
        .transact(xa)
        .mapError(e => DatabaseError(e))

    override def find(
      orgId: OrganizationId,
      vehicleId: Option[VehicleId],
      ruleId: Option[RuleId],
      from: Option[Instant],
      to: Option[Instant],
      status: Option[String],
      limit: Int
    ): IO[NotificationError, List[NotificationHistoryEntry]] =
      // Строим динамический SQL с фильтрами
      val baseQuery = fr"""SELECT id, rule_id, organization_id, event_type, vehicle_id,
                                  channel, recipient, status, message, error_message, created_at
                           FROM notification_history
                           WHERE organization_id = ${orgId.value}"""

      val vehicleFilter   = vehicleId.map(v => fr"AND vehicle_id = ${v.value}").getOrElse(fr"")
      val ruleFilter      = ruleId.map(r => fr"AND rule_id = ${r.value}").getOrElse(fr"")
      val fromFilter      = from.map(f => fr"AND created_at >= $f").getOrElse(fr"")
      val toFilter        = to.map(t => fr"AND created_at <= $t").getOrElse(fr"")
      val statusFilter    = status.map(s => fr"AND status = $s").getOrElse(fr"")
      val orderAndLimit   = fr"ORDER BY created_at DESC LIMIT $limit"

      val fullQuery = baseQuery ++ vehicleFilter ++ ruleFilter ++ fromFilter ++ toFilter ++ statusFilter ++ orderAndLimit

      fullQuery
        .query[(Long, Option[Long], Long, String, Long,
                String, String, String, String, Option[String], Instant)]
        .map { case (id, rid, oid, evType, vid, ch, rec, st, msg, err, ca) =>
          import zio.json.*
          NotificationHistoryEntry(
            id = id,
            ruleId = rid.map(RuleId(_)),
            organizationId = OrganizationId(oid),
            eventType = evType.fromJson[EventType].getOrElse(EventType.GeozoneEnter),
            vehicleId = VehicleId(vid),
            channel = ch.fromJson[Channel].getOrElse(Channel.Email),
            recipient = rec,
            status = st.fromJson[DeliveryStatus].getOrElse(DeliveryStatus.Failed),
            message = msg,
            errorMessage = err,
            createdAt = ca
          )
        }
        .to[List]
        .transact(xa)
        .mapError(e => DatabaseError(e))
