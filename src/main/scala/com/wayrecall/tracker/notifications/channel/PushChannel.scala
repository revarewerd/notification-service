package com.wayrecall.tracker.notifications.channel

import zio.*
import zio.http.{Client, Request, Response, Body, URL, Header, MediaType}
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.PushConfig

// ============================================================
// PUSH CHANNEL — Отправка push-уведомлений через Firebase FCM
//
// MVP: HTTP v1 API Firebase Cloud Messaging
// ============================================================

class PushChannel(config: PushConfig, client: Client) extends NotificationChannel:
  override val channelType: Channel = Channel.Push

  override def send(recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult] =
    if !config.enabled then
      ZIO.succeed(DeliveryResult.failed(Channel.Push, recipient, "Push канал отключён"))
    else
      sendViaFcm(recipient, message)
        .catchAll { case e: Throwable =>
          ZIO.logError(s"Push отправка провалилась на $recipient: ${e.getMessage}") *>
            ZIO.succeed(DeliveryResult.failed(Channel.Push, recipient, e.getMessage))
        }

  /** Отправка через Firebase HTTP v1 API */
  private def sendViaFcm(deviceToken: String, message: RenderedMessage): IO[Throwable, DeliveryResult] =
    // MVP: упрощённый вариант без OAuth2 аутентификации
    // В production — нужен Service Account + JWT для получения access token
    val payload =
      s"""{
         |  "message": {
         |    "token": "$deviceToken",
         |    "notification": {
         |      "title": "${message.subject.getOrElse("")}",
         |      "body": "${message.shortBody}"
         |    }
         |  }
         |}""".stripMargin

    val url = "https://fcm.googleapis.com/v1/projects/wayrecall-tracker/messages:send"
    for {
      req      <- ZIO.succeed(
                    Request.post(URL.decode(url).getOrElse(URL.empty), Body.fromString(payload))
                      .addHeader(Header.ContentType(MediaType.application.json))
                  )
      response <- ZIO.scoped(client.request(req)).mapError(e => new RuntimeException(s"FCM запрос не удался: $e"))
      body     <- response.body.asString.mapError(e => new RuntimeException(s"FCM ответ не читается: $e"))
      result   <- if response.status.isSuccess then
                    ZIO.logInfo(s"Push отправлен на $deviceToken") *>
                    ZIO.succeed(DeliveryResult.success(Channel.Push, deviceToken))
                  else
                    ZIO.succeed(DeliveryResult.failed(Channel.Push, deviceToken, s"FCM ответ: $body"))
    } yield result

object PushChannel:
  val live: ZLayer[PushConfig & Client, Nothing, PushChannel] =
    ZLayer.fromFunction((config: PushConfig, client: Client) => PushChannel(config, client))
