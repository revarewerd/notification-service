package com.wayrecall.tracker.notifications

import zio.*
import zio.http.*
import zio.kafka.consumer.{Consumer, ConsumerSettings}
import zio.kafka.consumer.diagnostics.Diagnostics
import com.wayrecall.tracker.notifications.config.*
import com.wayrecall.tracker.notifications.infrastructure.TransactorLayer
import com.wayrecall.tracker.notifications.repository.*
import com.wayrecall.tracker.notifications.storage.ThrottleService
import com.wayrecall.tracker.notifications.service.*
import com.wayrecall.tracker.notifications.channel.*
import com.wayrecall.tracker.notifications.kafka.EventConsumer
import com.wayrecall.tracker.notifications.api.*

// ============================================================
// MAIN — Точка входа Notification Service
//
// Порт: 8094 (REST API)
// Kafka consumer: geozone-events, rule-violations, device-status
// ============================================================

object Main extends ZIOAppDefault:

  // Используем дефолтный консольный логгер ZIO

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    val program = for {
      config <- ZIO.service[AppConfig]
      _      <- ZIO.logInfo("=" * 60)
      _      <- ZIO.logInfo("  Notification Service запускается...")
      _      <- ZIO.logInfo(s"  HTTP порт: ${config.http.port}")
      _      <- ZIO.logInfo(s"  Kafka топики: ${config.kafka.topics.mkString(", ")}")
      _      <- ZIO.logInfo("=" * 60)

      // Объединяем все HTTP маршруты
      allRoutes = HealthRoutes.routes ++ RuleRoutes.routes ++ TemplateRoutes.routes ++ HistoryRoutes.routes

      // Запуск HTTP сервера и Kafka consumer параллельно
      _ <- (
        Server.serve(allRoutes.toHttpApp).fork <*>
        EventConsumer.run.fork
      ).flatMap { case (httpFiber, kafkaFiber) =>
        ZIO.logInfo(s"HTTP сервер запущен на порту ${config.http.port}") *>
        ZIO.logInfo("Kafka consumer запущен") *>
        httpFiber.join.race(kafkaFiber.join)
      }
    } yield ()

    program.provide(
      // Конфигурация
      AppConfig.allLayers,

      // HTTP сервер
      Server.defaultWith(_.port(8094)),
      Client.default,

      // Kafka consumer
      kafkaConsumerLayer,

      // PostgreSQL
      TransactorLayer.live,

      // Репозитории
      RuleRepository.live,
      TemplateRepository.live,
      HistoryRepository.live,

      // Throttle и rate limiting
      ThrottleService.live,

      // Template engine
      TemplateEngine.live,

      // Каналы доставки
      EmailChannel.live,
      SmsChannel.live,
      PushChannel.live,
      TelegramChannel.live,
      WebhookChannel.live,
      DeliveryService.live,

      // Service layer
      RuleMatcher.live,
      NotificationOrchestrator.live,

      // Конфиг для каналов (extract из ChannelsConfig)
      smtpConfigLayer,
      smsConfigLayer,
      pushConfigLayer,
      telegramConfigLayer,
      webhookConfigLayer,

      // Scope
      Scope.default
    )

  // === Вспомогательные слои ===

  /** Kafka consumer settings из конфигурации */
  private val kafkaConsumerLayer: ZLayer[KafkaConfig, Throwable, Consumer] =
    ZLayer.fromZIO(
      ZIO.service[KafkaConfig].map { config =>
        ConsumerSettings(config.bootstrapServers.split(",").toList)
          .withGroupId(config.consumerGroupId)
      }
    ).flatMap { env =>
      ZLayer.succeed(env.get[ConsumerSettings]) ++ ZLayer.succeed(Diagnostics.NoOp) >>> Consumer.live
    }

  /** Извлечение конфигов каналов из ChannelsConfig */
  private val smtpConfigLayer: ZLayer[ChannelsConfig, Nothing, SmtpConfig] =
    ZLayer.fromFunction((ch: ChannelsConfig) => ch.email)

  private val smsConfigLayer: ZLayer[ChannelsConfig, Nothing, SmsConfig] =
    ZLayer.fromFunction((ch: ChannelsConfig) => ch.sms)

  private val pushConfigLayer: ZLayer[ChannelsConfig, Nothing, PushConfig] =
    ZLayer.fromFunction((ch: ChannelsConfig) => ch.push)

  private val telegramConfigLayer: ZLayer[ChannelsConfig, Nothing, TelegramConfig] =
    ZLayer.fromFunction((ch: ChannelsConfig) => ch.telegram)

  private val webhookConfigLayer: ZLayer[ChannelsConfig, Nothing, WebhookConfig] =
    ZLayer.fromFunction((ch: ChannelsConfig) => ch.webhook)
