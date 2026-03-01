package com.wayrecall.tracker.notifications.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.HistoryRepository
import com.wayrecall.tracker.notifications.service.NotificationOrchestrator
import java.time.Instant

// ============================================================
// HISTORY ROUTES — История уведомлений + тестовая отправка
//
// GET    /api/v1/history?orgId=...&vehicleId=...&from=...&to=...
// POST   /api/v1/test-send — тестовая отправка уведомления
// ============================================================

object HistoryRoutes:

  def routes: Routes[HistoryRepository & NotificationOrchestrator, Nothing] = Routes(
    // История уведомлений с фильтрами
    Method.GET / "api" / "v1" / "history" -> handler { (req: Request) =>
      withOrgId(req) { orgId =>
        val params    = req.url.queryParams
        val vehicleId = params.get("vehicleId").flatMap(_.headOption).flatMap(_.toLongOption).map(VehicleId(_))
        val ruleId    = params.get("ruleId").flatMap(_.headOption).flatMap(_.toLongOption).map(RuleId(_))
        val from      = params.get("from").flatMap(_.headOption).flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        val to        = params.get("to").flatMap(_.headOption).flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        val status    = params.get("status").flatMap(_.headOption)
        val limit     = params.get("limit").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(100)

        ZIO.serviceWithZIO[HistoryRepository](_.find(orgId, vehicleId, ruleId, from, to, status, limit))
          .map(entries => Response.json(entries.toJson))
          .catchAll(errorResponse)
      }
    },

    // Тестовая отправка уведомления
    Method.POST / "api" / "v1" / "test-send" -> handler { (req: Request) =>
      (for {
        body <- req.body.asString.orDie
        testReq <- ZIO.fromEither(body.fromJson[TestNotificationRequest])
                     .mapError(e => InvalidRule(s"Невалидный JSON: $e"))
        result  <- ZIO.serviceWithZIO[NotificationOrchestrator](_.testSend(testReq))
      } yield Response.json(result.toJson))
        .catchAll(errorResponse)
    }
  )

  private def withOrgId[R](req: Request)(f: OrganizationId => ZIO[R, NotificationError, Response]): ZIO[R, NotificationError, Response] =
    req.url.queryParams.get("orgId").flatMap(_.headOption).flatMap(_.toLongOption) match
      case Some(id) => f(OrganizationId(id))
      case None     => ZIO.succeed(Response.json("""{"error":"orgId is required"}""").status(Status.BadRequest))

  private def errorResponse(err: NotificationError): UIO[Response] =
    val (status, message) = err match
      case DeliveryError(ch, msg) => (Status.BadGateway, s"Ошибка доставки $ch: $msg")
      case ChannelDisabled(ch)    => (Status.BadRequest, s"Канал $ch отключён")
      case DatabaseError(e)       => (Status.InternalServerError, s"Ошибка БД: ${e.getMessage}")
      case MissingOrganizationId  => (Status.BadRequest, "orgId обязателен")
      case other                  => (Status.InternalServerError, other.toString)
    ZIO.succeed(Response.json(s"""{"error":"$message"}""").status(status))
