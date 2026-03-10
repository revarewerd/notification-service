package com.wayrecall.tracker.notifications.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.TemplateRepository
import java.time.Instant

// ============================================================
// ТЕСТЫ TEMPLATE ENGINE — рендеринг шаблонов {{variable}}
// ============================================================

object TemplateEngineSpec extends ZIOSpecDefault:

  // ---- Mock TemplateRepository (in-memory) ----

  class MockTemplateRepo(ref: Ref[Map[Long, NotificationTemplate]]) extends TemplateRepository:
    override def findAll(orgId: OrganizationId): IO[NotificationError, List[NotificationTemplate]] =
      ref.get.map(_.values.toList.filter(t =>
        t.organizationId.isEmpty || t.organizationId.contains(orgId)
      ))

    override def findById(id: TemplateId): IO[NotificationError, Option[NotificationTemplate]] =
      ref.get.map(_.get(id.value))

    override def findByEventType(orgId: OrganizationId, eventType: String): IO[NotificationError, Option[NotificationTemplate]] =
      ref.get.map(_.values.find(t =>
        t.eventType.toString.toLowerCase == eventType.toLowerCase &&
        (t.organizationId.isEmpty || t.organizationId.contains(orgId))
      ))

    override def create(orgId: OrganizationId, req: CreateTemplate): IO[NotificationError, NotificationTemplate] =
      for {
        id <- ref.get.map(_.size.toLong + 1)
        tmpl = NotificationTemplate(
          id = TemplateId(id), organizationId = Some(orgId), name = req.name,
          eventType = req.eventType,
          emailSubject = req.emailSubject, emailBody = req.emailBody,
          smsBody = req.smsBody, pushTitle = req.pushTitle, pushBody = req.pushBody,
          telegramBody = req.telegramBody, createdAt = Instant.now()
        )
        _ <- ref.update(_.updated(id, tmpl))
      } yield tmpl

    override def update(id: TemplateId, orgId: OrganizationId, req: CreateTemplate): IO[NotificationError, Option[NotificationTemplate]] =
      ZIO.succeed(None) // Для тестов не нужен

  object MockTemplateRepo:
    def layer(templates: NotificationTemplate*): ZLayer[Any, Nothing, TemplateRepository] =
      ZLayer.fromZIO(
        Ref.make(templates.map(t => t.id.value -> t).toMap).map(new MockTemplateRepo(_))
      )

  // ---- Тестовые шаблоны ----

  private val now = Instant.now()

  private val emailTemplate = NotificationTemplate(
    id = TemplateId(1L),
    organizationId = Some(OrganizationId(10L)),
    name = "Speed Alert",
    eventType = EventType.SpeedViolation,
    emailSubject = Some("Превышение скорости: {{vehicleName}}"),
    emailBody = Some("Транспорт {{vehicleName}} ехал со скоростью {{speed}} км/ч (лимит: {{speedLimit}})."),
    smsBody = Some("{{vehicleName}}: скорость {{speed}} (лимит {{speedLimit}})"),
    pushTitle = Some("Скорость!"),
    pushBody = Some("{{vehicleName}} — {{speed}} км/ч"),
    telegramBody = Some("🚨 {{vehicleName}} превысил скорость: {{speed}} км/ч"),
    createdAt = now
  )

  private val noSmsTemplate = NotificationTemplate(
    id = TemplateId(2L),
    organizationId = Some(OrganizationId(10L)),
    name = "Email Only",
    eventType = EventType.GeozoneEnter,
    emailSubject = Some("Геозона: {{geozoneName}}"),
    emailBody = Some("{{vehicleName}} въехал в {{geozoneName}}"),
    smsBody = None,
    pushTitle = None,
    pushBody = None,
    telegramBody = None,
    createdAt = now
  )

  private val testData = Map(
    "vehicleName" -> "КамАЗ А123БВ",
    "speed" -> "120",
    "speedLimit" -> "90",
    "geozoneName" -> "Склад-1"
  )

  private def testLayer(templates: NotificationTemplate*) =
    MockTemplateRepo.layer(templates*) >>> TemplateEngine.live

  def spec = suite("TemplateEngine")(

    // ---- render (по ID шаблона) ----
    suite("render — по ID шаблона")(
      test("email — подстановка переменных в subject и body") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(1L), Channel.Email, testData)
        } yield assertTrue(
          result.subject.contains("Превышение скорости: КамАЗ А123БВ"),
          result.body.contains("120 км/ч"),
          result.body.contains("лимит: 90")
        )
      }.provide(testLayer(emailTemplate)),

      test("sms — подстановка переменных") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(1L), Channel.Sms, testData)
        } yield assertTrue(
          result.body.contains("КамАЗ А123БВ"),
          result.body.contains("120"),
          result.subject.isEmpty // SMS нет subject
        )
      }.provide(testLayer(emailTemplate)),

      test("push — title и body") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(1L), Channel.Push, testData)
        } yield assertTrue(
          result.subject.contains("Скорость!"),
          result.body.contains("КамАЗ А123БВ")
        )
      }.provide(testLayer(emailTemplate)),

      test("telegram — body с emoji") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(1L), Channel.Telegram, testData)
        } yield assertTrue(
          result.body.contains("🚨"),
          result.body.contains("120 км/ч")
        )
      }.provide(testLayer(emailTemplate)),

      test("webhook — использует emailBody как fallback") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(1L), Channel.Webhook, testData)
        } yield assertTrue(
          result.body.contains("120 км/ч"),
          result.body.contains("лимит: 90")
        )
      }.provide(testLayer(emailTemplate)),

      test("ошибка TemplateNotFound — шаблон не существует") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(999L), Channel.Email, testData).either
        } yield assertTrue(result match
          case Left(TemplateNotFound(999L)) => true
          case _ => false
        )
      }.provide(testLayer(emailTemplate)),

      test("ошибка TemplateRenderError — нет body для канала") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.render(TemplateId(2L), Channel.Sms, testData).either
        } yield assertTrue(result match
          case Left(TemplateRenderError(_)) => true
          case _ => false
        )
      }.provide(testLayer(noSmsTemplate))
    ),

    // ---- renderCustom ----
    suite("renderCustom — пользовательское сообщение")(
      test("подстановка переменных {{key}}") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderCustom("Привет, {{vehicleName}}! Скорость: {{speed}}", testData)
        } yield assertTrue(
          result.body == "Привет, КамАЗ А123БВ! Скорость: 120",
          result.subject.isEmpty
        )
      }.provide(testLayer()),

      test("без переменных — текст без изменений") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderCustom("Простой текст без переменных", Map.empty)
        } yield assertTrue(result.body == "Простой текст без переменных")
      }.provide(testLayer()),

      test("shortBody — обрезка длинного текста до 160 символов") {
        val longMessage = "А" * 200
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderCustom(longMessage, Map.empty)
        } yield assertTrue(
          result.body.length == 200,
          result.shortBody.length == 160,
          result.shortBody.endsWith("...")
        )
      }.provide(testLayer()),

      test("shortBody — короткий текст не обрезается") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderCustom("Короткий текст", Map.empty)
        } yield assertTrue(
          result.body == "Короткий текст",
          result.shortBody == "Короткий текст"
        )
      }.provide(testLayer()),

      test("shortBody — ровно 160 символов не обрезается") {
        val exact160 = "Б" * 160
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderCustom(exact160, Map.empty)
        } yield assertTrue(
          result.shortBody.length == 160,
          !result.shortBody.endsWith("...")
        )
      }.provide(testLayer())
    ),

    // ---- renderByEventType ----
    suite("renderByEventType — автоматический выбор шаблона")(
      test("найден шаблон организации — использует его") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderByEventType(
            OrganizationId(10L), "SpeedViolation", Channel.Email, testData
          )
        } yield assertTrue(
          result.body.contains("120 км/ч"),
          result.subject.exists(_.contains("КамАЗ А123БВ"))
        )
      }.provide(testLayer(emailTemplate)),

      test("шаблон не найден — дефолтное сообщение geozone_enter") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderByEventType(
            OrganizationId(10L), "geozone_enter", Channel.Email,
            Map("vehicleName" -> "Газель", "geozoneName" -> "Офис", "time" -> "12:00")
          )
        } yield assertTrue(
          result.body.contains("Газель"),
          result.body.contains("Офис"),
          result.subject.isDefined // дефолтный subject
        )
      }.provide(testLayer()),

      test("шаблон не найден — дефолтное speed_violation") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderByEventType(
            OrganizationId(10L), "speed_violation", Channel.Sms,
            Map("vehicleName" -> "Газель", "speed" -> "110", "speedLimit" -> "80", "time" -> "14:30")
          )
        } yield assertTrue(
          result.body.contains("110"),
          result.body.contains("80")
        )
      }.provide(testLayer()),

      test("шаблон не найден — дефолтное device_offline") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderByEventType(
            OrganizationId(10L), "device_offline", Channel.Telegram,
            Map("vehicleName" -> "ЗИЛ", "time" -> "03:00")
          )
        } yield assertTrue(
          result.body.contains("ЗИЛ"),
          result.body.contains("оффлайн")
        )
      }.provide(testLayer()),

      test("неизвестный тип события — дефолтное сообщение") {
        for {
          engine <- ZIO.service[TemplateEngine]
          result <- engine.renderByEventType(
            OrganizationId(10L), "custom_event", Channel.Email,
            Map("vehicleName" -> "Авто", "time" -> "10:00")
          )
        } yield assertTrue(
          result.body.contains("custom_event"),
          result.body.contains("Авто")
        )
      }.provide(testLayer())
    )
  )
