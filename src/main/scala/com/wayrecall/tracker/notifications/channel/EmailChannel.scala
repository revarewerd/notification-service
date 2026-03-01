package com.wayrecall.tracker.notifications.channel

import zio.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.SmtpConfig
import java.util.Properties
import javax.mail.*
import javax.mail.internet.*

// ============================================================
// EMAIL CHANNEL — Отправка email через SMTP (JavaMail)
// ============================================================

class EmailChannel(config: SmtpConfig) extends NotificationChannel:
  override val channelType: Channel = Channel.Email

  override def send(recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult] =
    ZIO.attemptBlocking {
      // Настройки SMTP сессии
      val props = new Properties()
      props.put("mail.smtp.host", config.host)
      props.put("mail.smtp.port", config.port.toString)
      props.put("mail.smtp.auth", "true")
      if config.useTls then
        props.put("mail.smtp.starttls.enable", "true")

      val session = Session.getInstance(props, new Authenticator {
        override def getPasswordAuthentication: PasswordAuthentication =
          new PasswordAuthentication(config.username, config.password)
      })

      // Формируем письмо
      val msg = new MimeMessage(session)
      msg.setFrom(new InternetAddress(config.fromAddress, config.fromName))
      msg.setRecipients(Message.RecipientType.TO, recipient)
      msg.setSubject(message.subject)
      msg.setContent(message.body, "text/html; charset=utf-8")

      // Отправка
      Transport.send(msg)
    }
    .as(DeliveryResult.success(Channel.Email, recipient))
    .catchAll { case e: Throwable =>
      ZIO.logError(s"Email отправка провалилась на $recipient: ${e.getMessage}") *>
        ZIO.succeed(DeliveryResult.failed(Channel.Email, recipient, e.getMessage))
    }

object EmailChannel:
  val live: ZLayer[SmtpConfig, Nothing, EmailChannel] =
    ZLayer.fromFunction((config: SmtpConfig) => EmailChannel(config))
