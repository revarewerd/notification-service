package com.wayrecall.tracker.notifications.storage

import zio.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.RateLimitsConfig
import java.time.Instant

// ============================================================
// THROTTLE SERVICE — Дросселирование и rate limiting (in-memory)
//
// Хранит состояние в ZIO Ref (без Redis для MVP):
//   throttleRef: Map[String, Instant] — ключ → время истечения
//   rateRef: Map[String, Int]         — ключ → счётчик за час
// ============================================================

trait ThrottleService:
  /** Проверить дросселирование правила для ТС. true = можно отправить */
  def checkThrottle(ruleId: RuleId, vehicleId: VehicleId, throttleMinutes: Int): IO[NotificationError, Boolean]

  /** Проверить лимит отправки для пользователя по каналу. true = можно */
  def checkRateLimit(userId: UserId, channel: Channel): IO[NotificationError, Boolean]

  /** Пометить правило как отправленное (установить дросселирование) */
  def markThrottled(ruleId: RuleId, vehicleId: VehicleId, throttleMinutes: Int): IO[NotificationError, Unit]

  /** Инкрементировать счётчик отправок для rate limiting */
  def incrementRateCounter(userId: UserId, channel: Channel): IO[NotificationError, Unit]

object ThrottleService:

  val live: ZLayer[RateLimitsConfig, Nothing, ThrottleService] =
    ZLayer {
      for {
        limits      <- ZIO.service[RateLimitsConfig]
        throttleRef <- Ref.make(Map.empty[String, Instant])
        rateRef     <- Ref.make(Map.empty[String, Int])
      } yield Live(limits, throttleRef, rateRef)
    }

  private case class Live(
    limits: RateLimitsConfig,
    throttleRef: Ref[Map[String, Instant]],
    rateRef: Ref[Map[String, Int]]
  ) extends ThrottleService:

    private def throttleKey(ruleId: RuleId, vehicleId: VehicleId): String =
      s"throttle:${ruleId.value}:${vehicleId.value}"

    private def rateKey(userId: UserId, channel: Channel, hourBucket: Long): String =
      s"rate:${userId.value}:${channel.toString.toLowerCase}:$hourBucket"

    private def currentHourBucket: Long =
      Instant.now().getEpochSecond / 3600

    private def limitForChannel(channel: Channel): Int =
      channel match
        case Channel.Email    => limits.email
        case Channel.Sms      => limits.sms
        case Channel.Push     => limits.push
        case Channel.Telegram => limits.telegram
        case Channel.Webhook  => limits.webhook

    override def checkThrottle(ruleId: RuleId, vehicleId: VehicleId, throttleMinutes: Int): IO[NotificationError, Boolean] =
      if throttleMinutes <= 0 then ZIO.succeed(true)
      else
        val key = throttleKey(ruleId, vehicleId)
        val now = Instant.now()
        throttleRef.get.map { m =>
          m.get(key) match
            case Some(expiry) if expiry.isAfter(now) => false
            case _ => true
        }.tap(allowed => ZIO.logDebug(s"Дроссель: rule=${ruleId.value}, ТС=${vehicleId.value} → ${if allowed then "разрешено" else "дросселировано"}"))

    override def checkRateLimit(userId: UserId, channel: Channel): IO[NotificationError, Boolean] =
      val key = rateKey(userId, channel, currentHourBucket)
      val limit = limitForChannel(channel)
      rateRef.get.map(m => m.getOrElse(key, 0) < limit)
        .tap(allowed => ZIO.when(!allowed)(ZIO.logWarning(s"Рейт-лимит: превышен для user=${userId.value}, канал=$channel, лимит=$limit")))

    override def markThrottled(ruleId: RuleId, vehicleId: VehicleId, throttleMinutes: Int): IO[NotificationError, Unit] =
      if throttleMinutes <= 0 then ZIO.unit
      else
        val key = throttleKey(ruleId, vehicleId)
        val expiry = Instant.now().plusSeconds(throttleMinutes.toLong * 60)
        throttleRef.update(_.updated(key, expiry))

    override def incrementRateCounter(userId: UserId, channel: Channel): IO[NotificationError, Unit] =
      val key = rateKey(userId, channel, currentHourBucket)
      rateRef.update(m => m.updated(key, m.getOrElse(key, 0) + 1))
