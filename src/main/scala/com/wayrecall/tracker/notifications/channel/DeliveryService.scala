package com.wayrecall.tracker.notifications.channel

import zio.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*

// ============================================================
// DELIVERY SERVICE — Агрегатор каналов доставки
//
// Маршрутизирует Channel enum → конкретную реализацию канала.
// Отправляет по нескольким каналам параллельно.
// ============================================================

trait DeliveryService:
  /** Отправить уведомление в конкретный канал конкретному получателю */
  def send(channel: Channel, recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult]

  /** Отправить уведомление по всем каналам для всех получателей (параллельно) */
  def sendAll(deliveries: List[(Channel, String, RenderedMessage)]): IO[NotificationError, List[DeliveryResult]]

object DeliveryService:

  val live: ZLayer[
    EmailChannel & SmsChannel & PushChannel & TelegramChannel & WebhookChannel,
    Nothing,
    DeliveryService
  ] = ZLayer.fromFunction(
    (email: EmailChannel, sms: SmsChannel, push: PushChannel, telegram: TelegramChannel, webhook: WebhookChannel) =>
      Live(email, sms, push, telegram, webhook)
  )

  private case class Live(
    email: EmailChannel,
    sms: SmsChannel,
    push: PushChannel,
    telegram: TelegramChannel,
    webhook: WebhookChannel
  ) extends DeliveryService:

    /** Получить реализацию канала по типу */
    private def channelImpl(channel: Channel): NotificationChannel =
      channel match
        case Channel.Email    => email
        case Channel.Sms      => sms
        case Channel.Push     => push
        case Channel.Telegram => telegram
        case Channel.Webhook  => webhook

    override def send(channel: Channel, recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult] =
      ZIO.logDebug(s"Доставка: отправка через $channel → $recipient") *>
      channelImpl(channel).send(recipient, message)

    override def sendAll(deliveries: List[(Channel, String, RenderedMessage)]): IO[NotificationError, List[DeliveryResult]] =
      // Отправляем параллельно, но ограничиваем concurrency до 10
      ZIO.logInfo(s"Доставка: параллельная отправка ${deliveries.size} уведомлений по каналам: ${deliveries.map(_._1).distinct.mkString(", ")}") *>
      ZIO.foreachPar(deliveries) { case (channel, recipient, message) =>
        send(channel, recipient, message)
      }.withParallelism(10)
