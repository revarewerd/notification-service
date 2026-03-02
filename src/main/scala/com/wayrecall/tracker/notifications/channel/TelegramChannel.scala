package com.wayrecall.tracker.notifications.channel

import zio.*
import zio.http.{Client, Request, Response, Body, URL, Header, MediaType}
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.TelegramConfig

// ============================================================
// TELEGRAM CHANNEL — Отправка через Telegram Bot API
//
// https://api.telegram.org/bot<token>/sendMessage
// ============================================================

class TelegramChannel(config: TelegramConfig, client: Client) extends NotificationChannel:
  override val channelType: Channel = Channel.Telegram

  override def send(recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult] =
    if !config.enabled then
      ZIO.succeed(DeliveryResult.failed(Channel.Telegram, recipient, "Telegram канал отключён"))
    else
      sendViaTelegram(recipient, message.body)
        .catchAll { case e: Throwable =>
          ZIO.logError(s"Telegram отправка провалилась на chatId=$recipient: ${e.getMessage}") *>
            ZIO.succeed(DeliveryResult.failed(Channel.Telegram, recipient, e.getMessage))
        }

  /** Отправка через Telegram Bot API */
  private def sendViaTelegram(chatId: String, text: String): IO[Throwable, DeliveryResult] =
    val url = s"https://api.telegram.org/bot${config.botToken}/sendMessage"
    // Экранируем спец-символы для JSON
    val escapedText = text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")

    val payload =
      s"""{
         |  "chat_id": "$chatId",
         |  "text": "$escapedText",
         |  "parse_mode": "HTML"
         |}""".stripMargin

    for {
      req      <- ZIO.succeed(
                    Request.post(URL.decode(url).getOrElse(URL.empty), Body.fromString(payload))
                      .addHeader(Header.ContentType(MediaType.application.json))
                  )
      response <- ZIO.scoped(client.request(req)).mapError(e => new RuntimeException(s"Telegram запрос не удался: $e"))
      body     <- response.body.asString.mapError(e => new RuntimeException(s"Telegram ответ не читается: $e"))
      result   <- if response.status.isSuccess then
                    ZIO.logInfo(s"Telegram сообщение отправлено в chatId=$chatId") *>
                    ZIO.succeed(DeliveryResult.success(Channel.Telegram, chatId))
                  else
                    ZIO.succeed(DeliveryResult.failed(Channel.Telegram, chatId, s"Telegram ответ: $body"))
    } yield result

object TelegramChannel:
  val live: ZLayer[TelegramConfig & Client, Nothing, TelegramChannel] =
    ZLayer.fromFunction((config: TelegramConfig, client: Client) => TelegramChannel(config, client))
