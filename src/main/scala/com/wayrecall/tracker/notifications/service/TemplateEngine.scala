package com.wayrecall.tracker.notifications.service

import zio.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.TemplateRepository

// ============================================================
// TEMPLATE ENGINE — Рендеринг шаблонов {{variable}}
//
// MVP: простая подстановка переменных.
// Поддержка: {{vehicleName}}, {{speed}}, {{geozoneName}}, {{time}} и т.д.
// ============================================================

trait TemplateEngine:
  /** Рендер шаблона из БД по ID */
  def render(templateId: TemplateId, channel: Channel, data: Map[String, String]): IO[NotificationError, RenderedMessage]

  /** Рендер кастомного сообщения (без шаблона из БД) */
  def renderCustom(message: String, data: Map[String, String]): IO[NotificationError, RenderedMessage]

  /** Рендер по типу события (автоматический выбор шаблона) */
  def renderByEventType(orgId: OrganizationId, eventType: String, channel: Channel, data: Map[String, String]): IO[NotificationError, RenderedMessage]

object TemplateEngine:

  val live: ZLayer[TemplateRepository, Nothing, TemplateEngine] =
    ZLayer.fromFunction((repo: TemplateRepository) => Live(repo))

  private case class Live(repo: TemplateRepository) extends TemplateEngine:

    /** Подставить переменные {{key}} в строку */
    private def substitute(template: String, data: Map[String, String]): String =
      data.foldLeft(template) { case (text, (key, value)) =>
        text.replace(s"{{$key}}", value)
      }

    /** Извлечь тело шаблона для конкретного канала */
    private def bodyForChannel(tmpl: NotificationTemplate, channel: Channel): Option[String] =
      channel match
        case Channel.Email    => tmpl.emailBody
        case Channel.Sms      => tmpl.smsBody
        case Channel.Push     => tmpl.pushBody
        case Channel.Telegram => tmpl.telegramBody
        case Channel.Webhook  => tmpl.emailBody // webhook использует email body как fallback

    /** Извлечь subject/title для канала (если применимо) */
    private def subjectForChannel(tmpl: NotificationTemplate, channel: Channel): Option[String] =
      channel match
        case Channel.Email => tmpl.emailSubject
        case Channel.Push  => tmpl.pushTitle
        case _             => None

    override def render(templateId: TemplateId, channel: Channel, data: Map[String, String]): IO[NotificationError, RenderedMessage] =
      for {
        tmplOpt <- repo.findById(templateId)
        tmpl    <- ZIO.fromOption(tmplOpt).mapError(_ => TemplateNotFound(templateId.value))
        body    <- ZIO.fromOption(bodyForChannel(tmpl, channel))
                     .mapError(_ => TemplateRenderError(s"Шаблон ${templateId.value} не содержит тела для канала $channel"))
        subject   = subjectForChannel(tmpl, channel).map(s => substitute(s, data))
        rendered  = substitute(body, data)
        // Короткое тело для SMS/Push — обрезаем до 160 символов
        shortBody = if rendered.length > 160 then rendered.take(157) + "..." else rendered
      } yield RenderedMessage(
        subject = subject,
        body = rendered,
        shortBody = shortBody
      )

    override def renderCustom(message: String, data: Map[String, String]): IO[NotificationError, RenderedMessage] =
      val rendered = substitute(message, data)
      val shortBody = if rendered.length > 160 then rendered.take(157) + "..." else rendered
      ZIO.succeed(RenderedMessage(
        subject = None,
        body = rendered,
        shortBody = shortBody
      ))

    override def renderByEventType(orgId: OrganizationId, eventType: String, channel: Channel, data: Map[String, String]): IO[NotificationError, RenderedMessage] =
      for {
        tmplOpt <- repo.findByEventType(orgId, eventType)
        result  <- tmplOpt match
                     case Some(tmpl) =>
                       // Нашли шаблон — рендерим
                       val body     = bodyForChannel(tmpl, channel).getOrElse(s"Событие: $eventType")
                       val subject  = subjectForChannel(tmpl, channel).map(s => substitute(s, data))
                       val rendered = substitute(body, data)
                       val short    = if rendered.length > 160 then rendered.take(157) + "..." else rendered
                       ZIO.succeed(RenderedMessage(subject, rendered, short))
                     case None =>
                       // Нет шаблона — генерируем дефолтное сообщение
                       val defaultBody = generateDefaultMessage(eventType, data)
                       val short = if defaultBody.length > 160 then defaultBody.take(157) + "..." else defaultBody
                       ZIO.succeed(RenderedMessage(Some(s"Уведомление: $eventType"), defaultBody, short))
      } yield result

    /** Генерация дефолтного сообщения если шаблон не найден */
    private def generateDefaultMessage(eventType: String, data: Map[String, String]): String =
      val vehicle = data.getOrElse("vehicleName", data.getOrElse("vehicleId", "N/A"))
      val time    = data.getOrElse("time", "N/A")
      eventType match
        case "geozone_enter" =>
          val geozone = data.getOrElse("geozoneName", "N/A")
          s"Транспорт $vehicle въехал в геозону '$geozone' в $time"
        case "geozone_leave" =>
          val geozone = data.getOrElse("geozoneName", "N/A")
          s"Транспорт $vehicle покинул геозону '$geozone' в $time"
        case "speed_violation" =>
          val speed = data.getOrElse("speed", "N/A")
          val limit = data.getOrElse("speedLimit", "N/A")
          s"Транспорт $vehicle превысил скорость: $speed км/ч (лимит: $limit км/ч) в $time"
        case "device_offline" =>
          s"Устройство $vehicle перешло в оффлайн в $time"
        case "device_online" =>
          s"Устройство $vehicle снова онлайн в $time"
        case other =>
          s"Событие '$other' для $vehicle в $time"
