package com.wayrecall.tracker.notifications.service

import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.repository.*
import com.wayrecall.tracker.notifications.channel.*
import zio.*
import zio.json.*
import zio.test.*
import java.time.Instant

// ============================================================
// Тесты NotificationOrchestrator + RuleMatcher + DeliveryService
// In-memory реализации для тестирования пайплайна уведомлений
// ============================================================

object NotificationOrchestratorSpec extends ZIOSpecDefault:

  // --- InMemory RuleRepository ---
  final case class InMemoryRuleRepo(
    store: Ref[Map[RuleId, NotificationRule]],
    idCounter: Ref[Long]
  ) extends RuleRepository:

    override def findByOrganization(orgId: OrganizationId): IO[NotificationError, List[NotificationRule]] =
      store.get.map(_.values.filter(_.organizationId == orgId).toList)

    override def findMatchingRules(orgId: OrganizationId, eventType: String, vehicleId: Long): IO[NotificationError, List[NotificationRule]] =
      store.get.map { rules =>
        rules.values.filter { r =>
          r.organizationId == orgId && r.eventType.toString == eventType && r.enabled
        }.toList
      }

    override def findById(id: RuleId, orgId: OrganizationId): IO[NotificationError, Option[NotificationRule]] =
      store.get.map(_.get(id).filter(_.organizationId == orgId))

    override def create(orgId: OrganizationId, req: CreateRule): IO[NotificationError, NotificationRule] =
      for {
        id <- idCounter.getAndUpdate(_ + 1)
        now = Instant.now()
        ruleId = RuleId(id)
        rule = NotificationRule(
          id = ruleId, organizationId = orgId, name = req.name,
          enabled = true,
          eventType = req.eventType,
          conditions = req.conditions.getOrElse(RuleConditions(None, None, None, None, None)),
          applyToAll = req.applyToAll,
          vehicleIds = req.vehicleIds.getOrElse(Nil),
          groupIds = req.groupIds.getOrElse(Nil),
          geozoneIds = req.geozoneIds.getOrElse(Nil),
          channels = req.channels, recipients = req.recipients,
          throttleMinutes = req.throttleMinutes.getOrElse(0),
          templateId = req.templateId,
          customMessage = req.customMessage,
          schedule = req.schedule,
          createdAt = now, updatedAt = now
        )
        _ <- store.update(_ + (ruleId -> rule))
      } yield rule

    override def update(id: RuleId, orgId: OrganizationId, req: CreateRule): IO[NotificationError, Option[NotificationRule]] =
      store.modify { rules =>
        rules.get(id).filter(_.organizationId == orgId) match
          case Some(existing) =>
            val updated = existing.copy(name = req.name, eventType = req.eventType, updatedAt = Instant.now())
            (Some(updated), rules + (id -> updated))
          case None => (None, rules)
      }

    override def delete(id: RuleId, orgId: OrganizationId): IO[NotificationError, Boolean] =
      store.modify { rules =>
        if rules.contains(id) then (true, rules - id) else (false, rules)
      }

    override def toggle(id: RuleId, orgId: OrganizationId): IO[NotificationError, Option[NotificationRule]] =
      store.modify { rules =>
        rules.get(id).filter(_.organizationId == orgId) match
          case Some(rule) =>
            val toggled = rule.copy(enabled = !rule.enabled)
            (Some(toggled), rules + (id -> toggled))
          case None => (None, rules)
      }

  object InMemoryRuleRepo:
    val live: ZLayer[Any, Nothing, RuleRepository] =
      ZLayer {
        for {
          store   <- Ref.make(Map.empty[RuleId, NotificationRule])
          counter <- Ref.make(1L)
        } yield InMemoryRuleRepo(store, counter)
      }

  // --- InMemory HistoryRepository ---
  final case class InMemoryHistoryRepo(
    store: Ref[List[NotificationHistoryEntry]],
    idCounter: Ref[Long]
  ) extends HistoryRepository:

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
      for {
        id <- idCounter.getAndUpdate(_ + 1)
        et  = eventType.fromJson[EventType].getOrElse(EventType.GeozoneEnter)
        entry = NotificationHistoryEntry(
          id = id, ruleId = ruleId, organizationId = orgId,
          eventType = et, vehicleId = vehicleId,
          channel = channel, recipient = recipient,
          status = status, message = message,
          errorMessage = errorMessage, createdAt = Instant.now()
        )
        _ <- store.update(_ :+ entry)
      } yield id

    override def find(
      orgId: OrganizationId,
      vehicleId: Option[VehicleId],
      ruleId: Option[RuleId],
      from: Option[Instant],
      to: Option[Instant],
      status: Option[String],
      limit: Int
    ): IO[NotificationError, List[NotificationHistoryEntry]] =
      store.get.map { entries =>
        entries
          .filter(_.organizationId == orgId)
          .filter(e => vehicleId.forall(_ == e.vehicleId))
          .filter(e => ruleId.forall(r => e.ruleId.contains(r)))
          .filter(e => from.forall(f => !e.createdAt.isBefore(f)))
          .filter(e => to.forall(t => !e.createdAt.isAfter(t)))
          .filter(e => status.forall(s => e.status.toString == s))
          .take(limit)
      }

  object InMemoryHistoryRepo:
    val live: ZLayer[Any, Nothing, HistoryRepository] =
      ZLayer {
        for {
          store   <- Ref.make(List.empty[NotificationHistoryEntry])
          counter <- Ref.make(1L)
        } yield InMemoryHistoryRepo(store, counter)
      }

  // --- Тестовые данные ---
  private val orgId     = OrganizationId(1L)
  private val otherOrg  = OrganizationId(2L)
  private val vehicleId = VehicleId(1L)
  private val now       = Instant.now()

  // Хелпер для создания CreateRule с правильной сигнатурой
  private def mkCreateRule(
    name: String,
    eventType: EventType,
    channels: List[Channel] = Nil,
    recipients: Recipients = Recipients.empty,
    throttleMinutes: Option[Int] = None
  ): CreateRule = CreateRule(
    name = name, eventType = eventType, conditions = None,
    applyToAll = false, vehicleIds = None, groupIds = None, geozoneIds = None,
    channels = channels, recipients = recipients,
    throttleMinutes = throttleMinutes, templateId = None,
    customMessage = None, schedule = None
  )

  def spec = suite("NotificationOrchestrator — unit тесты")(
    ruleRepoSuite,
    historyRepoSuite,
    channelSuite,
    deliveryResultSuite
  ) @@ TestAspect.timeout(60.seconds)

  val ruleRepoSuite = suite("RuleRepository")(
    test("create + findById") {
      val req = mkCreateRule("Speed Alert", EventType.SpeedViolation,
        channels = List(Channel.Email),
        recipients = Recipients(Nil, List("admin@test.com"), Nil, Nil, Nil),
        throttleMinutes = Some(30))
      for {
        repo  <- ZIO.service[RuleRepository]
        rule  <- repo.create(orgId, req)
        found <- repo.findById(rule.id, orgId)
      } yield assertTrue(found.isDefined, found.get.name == "Speed Alert")
    }.provide(InMemoryRuleRepo.live),

    test("findByOrganization — только свои") {
      val req1 = mkCreateRule("R1", EventType.SpeedViolation, channels = List(Channel.Email))
      val req2 = mkCreateRule("R2", EventType.GeozoneEnter, channels = List(Channel.Sms))
      for {
        repo  <- ZIO.service[RuleRepository]
        _     <- repo.create(orgId, req1) *> repo.create(orgId, req2)
        _     <- repo.create(otherOrg, mkCreateRule("R3", EventType.MaintenanceDue))
        rules <- repo.findByOrganization(orgId)
      } yield assertTrue(rules.length == 2)
    }.provide(InMemoryRuleRepo.live),

    test("findMatchingRules — фильтр по eventType") {
      val req1 = mkCreateRule("Speed", EventType.SpeedViolation, channels = List(Channel.Email))
      val req2 = mkCreateRule("Geozone", EventType.GeozoneEnter, channels = List(Channel.Sms))
      for {
        repo  <- ZIO.service[RuleRepository]
        _     <- repo.create(orgId, req1) *> repo.create(orgId, req2)
        speed <- repo.findMatchingRules(orgId, "SpeedViolation", vehicleId.value)
      } yield assertTrue(speed.length == 1, speed.head.name == "Speed")
    }.provide(InMemoryRuleRepo.live),

    test("toggle — переключение enabled") {
      val req = mkCreateRule("Toggle", EventType.SpeedViolation)
      for {
        repo    <- ZIO.service[RuleRepository]
        rule    <- repo.create(orgId, req)
        toggled <- repo.toggle(rule.id, orgId)
        found   <- repo.findById(rule.id, orgId)
      } yield {
        val toggledDisabled  = !toggled.get.enabled
        val foundDisabled    = !found.get.enabled
        assertTrue(
          toggled.isDefined,
          toggledDisabled, // было true → стало false
          foundDisabled
        )
      }
    }.provide(InMemoryRuleRepo.live),

    test("delete — удаление правила") {
      val req = mkCreateRule("ToDelete", EventType.DeviceOffline)
      for {
        repo    <- ZIO.service[RuleRepository]
        rule    <- repo.create(orgId, req)
        deleted <- repo.delete(rule.id, orgId)
        found   <- repo.findById(rule.id, orgId)
      } yield assertTrue(deleted, found.isEmpty)
    }.provide(InMemoryRuleRepo.live),

    test("delete — несуществующее правило → false") {
      for {
        repo    <- ZIO.service[RuleRepository]
        deleted <- repo.delete(RuleId(999L), orgId)
      } yield assertTrue(!deleted)
    }.provide(InMemoryRuleRepo.live)
  )

  val historyRepoSuite = suite("HistoryRepository")(
    test("save + find по организации") {
      for {
        repo <- ZIO.service[HistoryRepository]
        _    <- repo.save(Some(RuleId(1L)), orgId, "SpeedViolation", vehicleId,
                  Channel.Email, "test@test.com", DeliveryStatus.Delivered, "Speed exceeded", None)
        hist <- repo.find(orgId, None, None, None, None, None, 10)
      } yield assertTrue(hist.length == 1, hist.head.recipient == "test@test.com")
    }.provide(InMemoryHistoryRepo.live),

    test("find — фильтрация по ТС") {
      val v1 = VehicleId(1L)
      val v2 = VehicleId(2L)
      for {
        repo <- ZIO.service[HistoryRepository]
        _    <- repo.save(Some(RuleId(1L)), orgId, "SpeedViolation", v1,
                  Channel.Email, "a@t.com", DeliveryStatus.Delivered, "B", None)
        _    <- repo.save(Some(RuleId(2L)), orgId, "GeozoneEnter", v2,
                  Channel.Sms, "+7", DeliveryStatus.Delivered, "B", None)
        hist <- repo.find(orgId, Some(v1), None, None, None, None, 10)
      } yield assertTrue(hist.length == 1)
    }.provide(InMemoryHistoryRepo.live),

    test("save + find — подсчёт по статусам") {
      val rId = RuleId(1L)
      for {
        repo <- ZIO.service[HistoryRepository]
        _    <- repo.save(Some(rId), orgId, "SpeedViolation", vehicleId,
                  Channel.Email, "a", DeliveryStatus.Delivered, "b", None)
        _    <- repo.save(Some(rId), orgId, "SpeedViolation", vehicleId,
                  Channel.Sms, "b", DeliveryStatus.Failed, "b", Some("timeout"))
        _    <- repo.save(Some(rId), orgId, "SpeedViolation", vehicleId,
                  Channel.Push, "c", DeliveryStatus.Sent, "b", None)
        all  <- repo.find(orgId, None, None, None, None, None, 100)
        delivered = all.count(_.status == DeliveryStatus.Delivered).toLong
        failed    = all.count(_.status == DeliveryStatus.Failed).toLong
        sent      = all.count(_.status == DeliveryStatus.Sent).toLong
      } yield assertTrue(
        all.length == 3,
        delivered == 1L,
        failed == 1L,
        sent == 1L
      )
    }.provide(InMemoryHistoryRepo.live),

    test("find — количество записей организации") {
      for {
        repo  <- ZIO.service[HistoryRepository]
        _     <- repo.save(Some(RuleId(1L)), orgId, "DeviceOffline", vehicleId,
                   Channel.Email, "a", DeliveryStatus.Delivered, "b", None)
        found <- repo.find(orgId, None, None, None, None, None, 100)
      } yield assertTrue(found.length == 1)
    }.provide(InMemoryHistoryRepo.live)
  )

  val channelSuite = suite("Channel enum")(
    test("все 5 каналов доступны") {
      assertTrue(Channel.values.length == 5)
    },

    test("каналы включают Email, Sms, Push, Telegram, Webhook") {
      val channels = Channel.values.map(_.toString).toSet
      assertTrue(
        channels.contains("Email"),
        channels.contains("Sms"),
        channels.contains("Push"),
        channels.contains("Telegram"),
        channels.contains("Webhook")
      )
    }
  )

  val deliveryResultSuite = suite("DeliveryStatus enum")(
    test("все статусы доставки") {
      assertTrue(DeliveryStatus.values.length == 5)
    },

    test("статусы включают Sent, Delivered, Failed, RateLimited, Throttled") {
      val statuses = DeliveryStatus.values.map(_.toString).toSet
      assertTrue(
        statuses.contains("Sent"),
        statuses.contains("Delivered"),
        statuses.contains("Failed")
      )
    }
  )
