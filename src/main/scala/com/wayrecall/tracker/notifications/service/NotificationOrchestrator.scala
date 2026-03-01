package com.wayrecall.tracker.notifications.service

import zio.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.{RuleRepository, TemplateRepository, HistoryRepository}
import com.wayrecall.tracker.notifications.storage.ThrottleService
import com.wayrecall.tracker.notifications.channel.DeliveryService

// ============================================================
// NOTIFICATION ORCHESTRATOR — Главный оркестратор уведомлений
//
// Поток обработки:
//   1. Event → RuleMatcher → список подходящих правил
//   2. Для каждого правила:
//      a. Проверить throttle (Redis SETEX)
//      b. Выбрать шаблон и отрендерить сообщение
//      c. Для каждого канала × получатель:
//         - Проверить rate limit
//         - Отправить через DeliveryService
//         - Сохранить результат в историю
//      d. Установить throttle маркер
// ============================================================

trait NotificationOrchestrator:
  /** Обработать входящее событие (из Kafka) */
  def processEvent(event: NotificationEvent): IO[NotificationError, List[DeliveryResult]]

  /** Тестовая отправка (из REST API) */
  def testSend(req: TestNotificationRequest): IO[NotificationError, DeliveryResult]

object NotificationOrchestrator:

  val live: ZLayer[
    RuleMatcher & TemplateEngine & ThrottleService & DeliveryService & HistoryRepository,
    Nothing,
    NotificationOrchestrator
  ] = ZLayer.fromFunction(
    (matcher: RuleMatcher, templateEngine: TemplateEngine, throttle: ThrottleService,
     delivery: DeliveryService, history: HistoryRepository) =>
      Live(matcher, templateEngine, throttle, delivery, history)
  )

  private case class Live(
    matcher: RuleMatcher,
    templateEngine: TemplateEngine,
    throttle: ThrottleService,
    delivery: DeliveryService,
    history: HistoryRepository
  ) extends NotificationOrchestrator:

    override def processEvent(event: NotificationEvent): IO[NotificationError, List[DeliveryResult]] =
      for {
        _     <- ZIO.logInfo(s"Обработка события ${event.eventType} vehicle=${event.vehicleId.value} org=${event.organizationId.value}")
        // 1. Найти подходящие правила
        rules <- matcher.findMatchingRules(event)
        _     <- ZIO.logDebug(s"Найдено ${rules.size} подходящих правил")
        // 2. Обработать каждое правило
        results <- ZIO.foreach(rules)(rule => processRule(rule, event))
      } yield results.flatten

    /** Обработка одного правила */
    private def processRule(rule: NotificationRule, event: NotificationEvent): IO[NotificationError, List[DeliveryResult]] =
      for {
        // Проверяем throttle
        canSend <- throttle.checkThrottle(rule.id, event.vehicleId, rule.throttleMinutes)
        results <- if !canSend then
                     ZIO.logDebug(s"Правило ${rule.id.value} дросселировано для vehicle=${event.vehicleId.value}") *>
                     ZIO.succeed(List(DeliveryResult.throttled(Channel.Email, "throttled")))
                   else
                     for {
                       // Рендерим сообщения для каждого канала
                       deliveries <- prepareDeliveries(rule, event)
                       // Отправляем параллельно
                       results    <- delivery.sendAll(deliveries)
                       // Сохраняем в историю
                       _          <- ZIO.foreach(results)(r => saveToHistory(rule, event, r))
                       // Устанавливаем throttle маркер
                       _          <- throttle.markThrottled(rule.id, event.vehicleId, rule.throttleMinutes)
                     } yield results
      } yield results

    /** Подготовить список доставок: (канал, получатель, сообщение) */
    private def prepareDeliveries(rule: NotificationRule, event: NotificationEvent): IO[NotificationError, List[(Channel, String, RenderedMessage)]] =
      import zio.json.*
      val eventTypeStr = event.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      ZIO.foreach(rule.channels) { channel =>
        for {
          // Рендерим шаблон для канала
          message <- rule.templateId match
                       case Some(tmplId) => templateEngine.render(tmplId, channel, event.payload)
                       case None =>
                         rule.customMessage match
                           case Some(custom) => templateEngine.renderCustom(custom, event.payload)
                           case None         => templateEngine.renderByEventType(event.organizationId, eventTypeStr, channel, event.payload)
          // Получаем получателей для данного канала
          recipients = recipientsForChannel(rule.recipients, channel)
        } yield recipients.map(r => (channel, r, message))
      }.map(_.flatten)

    /** Получить список получателей для конкретного канала */
    private def recipientsForChannel(recipients: Recipients, channel: Channel): List[String] =
      channel match
        case Channel.Email    => recipients.emails
        case Channel.Sms      => recipients.phones
        case Channel.Telegram => recipients.telegramChatIds
        case Channel.Webhook  => recipients.webhookUrls
        case Channel.Push     => Nil // Push recipients определяются через userId → device tokens (PostMVP)

    /** Сохранить результат доставки в историю */
    private def saveToHistory(rule: NotificationRule, event: NotificationEvent, result: DeliveryResult): IO[NotificationError, Unit] =
      import zio.json.*
      val eventTypeStr = event.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      history.save(
        ruleId = Some(rule.id),
        orgId = event.organizationId,
        eventType = eventTypeStr,
        vehicleId = event.vehicleId,
        channel = result.channel,
        recipient = result.recipient,
        status = result.status,
        message = result.message.getOrElse(""),
        errorMessage = result.errorMessage
      ).unit

    override def testSend(req: TestNotificationRequest): IO[NotificationError, DeliveryResult] =
      for {
        message <- templateEngine.renderCustom(req.message, Map.empty)
        result  <- delivery.send(req.channel, req.recipient, message)
        _       <- ZIO.logInfo(s"Тестовая отправка: ${req.channel} → ${req.recipient} = ${result.status}")
      } yield result
