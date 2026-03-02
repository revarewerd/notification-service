package com.wayrecall.tracker.notifications.channel

import zio.*
import zio.http.{Client, Request, Response, Body, URL, Header, MediaType}
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.SmsConfig

// ============================================================
// SMS CHANNEL — Отправка SMS через HTTP API (SMS.ru / Twilio)
//
// MVP: поддержка SMS.ru API. Twilio — PostMVP.
// ============================================================

class SmsChannel(config: SmsConfig, client: Client) extends NotificationChannel:
  override val channelType: Channel = Channel.Sms

  override def send(recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult] =
    if !config.enabled then
      ZIO.succeed(DeliveryResult.failed(Channel.Sms, recipient, "SMS канал отключён"))
    else
      sendViaSmsRu(recipient, message.shortBody)
        .catchAll { case e: Throwable =>
          ZIO.logError(s"SMS отправка провалилась на $recipient: ${e.getMessage}") *>
            ZIO.succeed(DeliveryResult.failed(Channel.Sms, recipient, e.getMessage))
        }

  /** Отправка через SMS.ru API */
  private def sendViaSmsRu(phone: String, text: String): IO[Throwable, DeliveryResult] =
    val url = s"https://sms.ru/sms/send?api_id=${config.apiKey}&to=$phone&msg=${java.net.URLEncoder.encode(text, "UTF-8")}&json=1"
    for {
      req      <- ZIO.succeed(Request.get(URL.decode(url).getOrElse(URL.empty)))
      response <- ZIO.scoped(client.request(req)).mapError(e => new RuntimeException(s"SMS.ru запрос не удался: $e"))
      body     <- response.body.asString.mapError(e => new RuntimeException(s"SMS.ru ответ не читается: $e"))
      result   <- if response.status.isSuccess then
                    ZIO.logInfo(s"SMS отправлен на $phone") *>
                    ZIO.succeed(DeliveryResult.success(Channel.Sms, phone))
                  else
                    ZIO.succeed(DeliveryResult.failed(Channel.Sms, phone, s"SMS.ru ответ: $body"))
    } yield result

object SmsChannel:
  val live: ZLayer[SmsConfig & Client, Nothing, SmsChannel] =
    ZLayer.fromFunction((config: SmsConfig, client: Client) => SmsChannel(config, client))
