package com.wayrecall.tracker.notifications.storage

import zio.*
import zio.redis.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.config.RateLimitsConfig
import java.time.Instant

// ============================================================
// THROTTLE SERVICE — Дросселирование и rate limiting через Redis
//
// Ключи Redis:
//   throttle:{ruleId}:{vehicleId}   — SETEX, TTL = throttle_minutes
//   rate:{userId}:{channel}:{hour}  — INCR + EXPIRE 3600
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

  val live: ZLayer[Redis & RateLimitsConfig, Nothing, ThrottleService] =
    ZLayer.fromFunction((redis: Redis, limits: RateLimitsConfig) => Live(redis, limits))

  private case class Live(redis: Redis, limits: RateLimitsConfig) extends ThrottleService:

    private def throttleKey(ruleId: RuleId, vehicleId: VehicleId): String =
      s"throttle:${ruleId.value}:${vehicleId.value}"

    private def rateKey(userId: UserId, channel: Channel, hourBucket: Long): String =
      s"rate:${userId.value}:${channel.toString.toLowerCase}:$hourBucket"

    private def currentHourBucket: Long =
      Instant.now().getEpochSecond / 3600

    /** Лимит для канала (кол-во сообщений в час) */
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
        redis.exists(key)
          .map(count => count == 0L) // если ключ НЕ существует — можно отправить
          .mapError(e => RedisError(e))

    override def checkRateLimit(userId: UserId, channel: Channel): IO[NotificationError, Boolean] =
      val key = rateKey(userId, channel, currentHourBucket)
      val limit = limitForChannel(channel)
      redis.get(key).returning[String]
        .map {
          case Some(countStr) => countStr.toIntOption.getOrElse(0) < limit
          case None           => true // ещё нет счётчика — можно
        }
        .mapError(e => RedisError(e))

    override def markThrottled(ruleId: RuleId, vehicleId: VehicleId, throttleMinutes: Int): IO[NotificationError, Unit] =
      if throttleMinutes <= 0 then ZIO.unit
      else
        val key = throttleKey(ruleId, vehicleId)
        val ttl = java.time.Duration.ofMinutes(throttleMinutes.toLong)
        redis.setEx(key, ttl, "1")
          .mapError(e => RedisError(e))
          .unit

    override def incrementRateCounter(userId: UserId, channel: Channel): IO[NotificationError, Unit] =
      val key = rateKey(userId, channel, currentHourBucket)
      val op = for {
        count <- redis.incr(key)
        // Устанавливаем TTL только если это первый инкремент (новый ключ)
        _     <- ZIO.when(count == 1L)(
                   redis.expire(key, java.time.Duration.ofSeconds(3600))
                 )
      } yield ()
      op.mapError(e => RedisError(e))
