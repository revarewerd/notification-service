package com.wayrecall.tracker.notifications.channel

import zio.*
import zio.http.{Client, Request, Response, Body, URL, Header, MediaType}
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.WebhookConfig

// ============================================================
// WEBHOOK CHANNEL — HTTP POST на внешний URL
//
// Retry с exponential backoff при ошибке доставки
// ============================================================

class WebhookChannel(config: WebhookConfig, client: Client) extends NotificationChannel:
  override val channelType: Channel = Channel.Webhook

  override def send(recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult] =
    if !config.enabled then
      ZIO.succeed(DeliveryResult.failed(Channel.Webhook, recipient, "Webhook канал отключён"))
    else
      sendWithRetry(recipient, message, retriesLeft = config.maxRetries)
        .catchAll { case e: Throwable =>
          ZIO.logError(s"Webhook отправка провалилась на $recipient: ${e.getMessage}") *>
            ZIO.succeed(DeliveryResult.failed(Channel.Webhook, recipient, e.getMessage))
        }

  /** Отправка с retry и exponential backoff */
  private def sendWithRetry(url: String, message: RenderedMessage, retriesLeft: Int): IO[Throwable, DeliveryResult] =
    val payload =
      s"""{
         |  "subject": "${message.subject.getOrElse("").replace("\"", "\\\"")}",
         |  "body": "${message.body.replace("\"", "\\\"").replace("\n", "\\n")}",
         |  "timestamp": "${java.time.Instant.now()}"
         |}""".stripMargin

    val request = Request.post(URL.decode(url).getOrElse(URL.empty), Body.fromString(payload))
      .addHeader(Header.ContentType(MediaType.application.json))

    ZIO.scoped(client.request(request))
      .mapError(e => new RuntimeException(s"Webhook запрос не удался: $e"))
      .timeoutFail(new RuntimeException(s"Webhook таймаут: ${config.timeoutSeconds}s"))(
        Duration.fromSeconds(config.timeoutSeconds.toLong)
      )
      .flatMap { response =>
        if response.status.isSuccess then
          ZIO.logInfo(s"Webhook доставлен на $url") *>
          ZIO.succeed(DeliveryResult.success(Channel.Webhook, url))
        else if retriesLeft > 0 then
          // Exponential backoff: 1s, 2s, 4s, 8s...
          val delay = Duration.fromSeconds(Math.pow(2, config.maxRetries - retriesLeft).toLong)
          ZIO.logWarning(s"Webhook $url ответил ${response.status}, повтор через ${delay.getSeconds}s (осталось $retriesLeft)") *>
          ZIO.sleep(delay) *> sendWithRetry(url, message, retriesLeft - 1)
        else
          response.body.asString.mapError(e => new RuntimeException(e.toString)).flatMap { body =>
            ZIO.succeed(DeliveryResult.failed(Channel.Webhook, url, s"HTTP ${response.status}: $body"))
          }
      }

object WebhookChannel:
  val live: ZLayer[WebhookConfig & Client, Nothing, WebhookChannel] =
    ZLayer.fromFunction((config: WebhookConfig, client: Client) => WebhookChannel(config, client))
