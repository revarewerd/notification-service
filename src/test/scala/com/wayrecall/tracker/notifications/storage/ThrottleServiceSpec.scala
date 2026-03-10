package com.wayrecall.tracker.notifications.storage

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.config.RateLimitsConfig

// ============================================================
// ТЕСТЫ THROTTLE SERVICE — дросселирование и rate limiting
// ============================================================

object ThrottleServiceSpec extends ZIOSpecDefault:

  // Тестовый конфиг: лимиты в час на пользователя
  private val testLimits = RateLimitsConfig(
    email = 100,
    sms = 10,
    push = 50,
    telegram = 30,
    webhook = 200
  )

  private val testLayer = ZLayer.succeed(testLimits) >>> ThrottleService.live

  def spec = suite("ThrottleService")(

    // ---- checkThrottle ----
    suite("checkThrottle — дросселирование правила")(
      test("без дросселирования (throttleMinutes = 0) — всегда разрешено") {
        for {
          svc <- ZIO.service[ThrottleService]
          ok  <- svc.checkThrottle(RuleId(1L), VehicleId(42L), 0)
        } yield assertTrue(ok)
      }.provide(testLayer),

      test("отрицательный throttleMinutes — разрешено") {
        for {
          svc <- ZIO.service[ThrottleService]
          ok  <- svc.checkThrottle(RuleId(1L), VehicleId(42L), -5)
        } yield assertTrue(ok)
      }.provide(testLayer),

      test("первый раз — всегда разрешено") {
        for {
          svc <- ZIO.service[ThrottleService]
          ok  <- svc.checkThrottle(RuleId(1L), VehicleId(42L), 30)
        } yield assertTrue(ok)
      }.provide(testLayer),

      test("после markThrottled — заблокировано") {
        for {
          svc     <- ZIO.service[ThrottleService]
          _       <- svc.markThrottled(RuleId(1L), VehicleId(42L), 30)
          blocked <- svc.checkThrottle(RuleId(1L), VehicleId(42L), 30)
        } yield assertTrue(!blocked)
      }.provide(testLayer),

      test("разные правила — независимый дроссель") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- svc.markThrottled(RuleId(1L), VehicleId(42L), 30)
          ok  <- svc.checkThrottle(RuleId(2L), VehicleId(42L), 30)
        } yield assertTrue(ok) // Другое правило — не заблокировано
      }.provide(testLayer),

      test("разные ТС — независимый дроссель") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- svc.markThrottled(RuleId(1L), VehicleId(42L), 30)
          ok  <- svc.checkThrottle(RuleId(1L), VehicleId(99L), 30)
        } yield assertTrue(ok) // Другое ТС — не заблокировано
      }.provide(testLayer),

      test("markThrottled с throttleMinutes = 0 — не блокирует") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- svc.markThrottled(RuleId(1L), VehicleId(42L), 0)
          ok  <- svc.checkThrottle(RuleId(1L), VehicleId(42L), 30)
        } yield assertTrue(ok) // 0 минут — не записывает блокировку
      }.provide(testLayer)
    ),

    // ---- checkRateLimit ----
    suite("checkRateLimit — лимит отправки в час")(
      test("первый раз — разрешено") {
        for {
          svc <- ZIO.service[ThrottleService]
          ok  <- svc.checkRateLimit(UserId(1L), Channel.Email)
        } yield assertTrue(ok)
      }.provide(testLayer),

      test("после incrementRateCounter — ещё разрешено (не превышен)") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- svc.incrementRateCounter(UserId(1L), Channel.Email)
          ok  <- svc.checkRateLimit(UserId(1L), Channel.Email)
        } yield assertTrue(ok) // 1 из 100 — разрешено
      }.provide(testLayer),

      test("SMS лимит 10 — после 10 инкрементов блокируется") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- ZIO.foreach(1 to 10)(_ => svc.incrementRateCounter(UserId(1L), Channel.Sms))
          ok  <- svc.checkRateLimit(UserId(1L), Channel.Sms)
        } yield assertTrue(!ok) // 10 из 10 — лимит исчерпан
      }.provide(testLayer),

      test("разные каналы — независимый счётчик") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- ZIO.foreach(1 to 10)(_ => svc.incrementRateCounter(UserId(1L), Channel.Sms))
          ok  <- svc.checkRateLimit(UserId(1L), Channel.Email) // Email лимит 100
        } yield assertTrue(ok) // SMS исчерпан, но Email свободен
      }.provide(testLayer),

      test("разные пользователи — независимый счётчик") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- ZIO.foreach(1 to 10)(_ => svc.incrementRateCounter(UserId(1L), Channel.Sms))
          ok  <- svc.checkRateLimit(UserId(2L), Channel.Sms)
        } yield assertTrue(ok) // Другой пользователь — свой счётчик
      }.provide(testLayer),

      test("Push лимит 50 — 49 инкрементов, ещё разрешено") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- ZIO.foreach(1 to 49)(_ => svc.incrementRateCounter(UserId(5L), Channel.Push))
          ok  <- svc.checkRateLimit(UserId(5L), Channel.Push)
        } yield assertTrue(ok) // 49 из 50 — ещё можно
      }.provide(testLayer),

      test("Telegram лимит 30 — 30 инкрементов, заблокировано") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- ZIO.foreach(1 to 30)(_ => svc.incrementRateCounter(UserId(5L), Channel.Telegram))
          ok  <- svc.checkRateLimit(UserId(5L), Channel.Telegram)
        } yield assertTrue(!ok) // 30 из 30 — лимит
      }.provide(testLayer)
    ),

    // ---- incrementRateCounter ----
    suite("incrementRateCounter — инкремент счётчика")(
      test("множественный инкремент аккумулируется") {
        for {
          svc <- ZIO.service[ThrottleService]
          _   <- ZIO.foreach(1 to 5)(_ => svc.incrementRateCounter(UserId(3L), Channel.Email))
          // После 5 инкрементов ещё сильно ниже лимита 100
          ok  <- svc.checkRateLimit(UserId(3L), Channel.Email)
        } yield assertTrue(ok)
      }.provide(testLayer)
    )
  )
