package com.wayrecall.tracker.notifications.config

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*

// ============================================================
// КОНФИГУРАЦИЯ NOTIFICATION SERVICE
// ============================================================

/** Kafka consumer */
final case class KafkaConfig(
    bootstrapServers: String,
    consumerGroupId: String,
    topics: List[String]            // geozone-events, rule-violations, device-status
)

/** PostgreSQL */
final case class DatabaseConfig(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int
)

/** Redis */
final case class RedisConfig(
    host: String,
    port: Int
)

/** HTTP сервер */
final case class HttpConfig(
    port: Int
)

/** SMTP (email) */
final case class SmtpConfig(
    host: String,
    port: Int,
    username: String,
    password: String,
    fromAddress: String,
    fromName: String,
    useTls: Boolean
)

/** SMS */
final case class SmsConfig(
    enabled: Boolean,
    provider: String,      // "smsru", "twilio"
    apiKey: String
)

/** Push (FCM) */
final case class PushConfig(
    enabled: Boolean,
    credentialsFile: String
)

/** Telegram */
final case class TelegramConfig(
    enabled: Boolean,
    botToken: String
)

/** Webhook */
final case class WebhookConfig(
    enabled: Boolean,
    timeoutSeconds: Int,
    maxRetries: Int
)

/** Каналы доставки — агрегат */
final case class ChannelsConfig(
    email: SmtpConfig,
    sms: SmsConfig,
    push: PushConfig,
    telegram: TelegramConfig,
    webhook: WebhookConfig
)

/** Rate limits (per hour per user) */
final case class RateLimitsConfig(
    email: Int,
    sms: Int,
    push: Int,
    telegram: Int,
    webhook: Int
)

/** Корневая конфигурация */
final case class AppConfig(
    kafka: KafkaConfig,
    database: DatabaseConfig,
    redis: RedisConfig,
    http: HttpConfig,
    channels: ChannelsConfig,
    rateLimits: RateLimitsConfig
)

object AppConfig:

  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      ZIO.config[AppConfig](
        deriveConfig[AppConfig].mapKey(toKebabCase)
      )
    )

  /** Производные слои для инжекции подконфигураций */
  val allLayers: ZLayer[Any, Config.Error, AppConfig & KafkaConfig & DatabaseConfig & RedisConfig & HttpConfig & ChannelsConfig & RateLimitsConfig] =
    live.flatMap { env =>
      val config = env.get[AppConfig]
      ZLayer.succeed(config) ++
      ZLayer.succeed(config.kafka) ++
      ZLayer.succeed(config.database) ++
      ZLayer.succeed(config.redis) ++
      ZLayer.succeed(config.http) ++
      ZLayer.succeed(config.channels) ++
      ZLayer.succeed(config.rateLimits)
    }
