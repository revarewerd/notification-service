package com.wayrecall.tracker.notifications.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.TemplateRepository

// ============================================================
// TEMPLATE ROUTES — CRUD шаблонов уведомлений
//
// GET    /api/v1/templates?orgId=...       — список шаблонов
// POST   /api/v1/templates?orgId=...       — создать шаблон
// PUT    /api/v1/templates/:id?orgId=...   — обновить шаблон
// ============================================================

object TemplateRoutes:

  def routes: Routes[TemplateRepository, Nothing] = Routes(
    // Все шаблоны организации (включая системные)
    Method.GET / "api" / "v1" / "templates" -> handler { (req: Request) =>
      withOrgId(req) { orgId =>
        ZIO.serviceWithZIO[TemplateRepository](_.findAll(orgId))
          .map(templates => Response.json(templates.toJson))
      }.catchAll(errorResponse)
    },

    // Создать шаблон
    Method.POST / "api" / "v1" / "templates" -> handler { (req: Request) =>
      withOrgId(req) { orgId =>
        for {
          body   <- req.body.asString.orDie
          create <- ZIO.fromEither(body.fromJson[CreateTemplate])
                      .mapError(e => InvalidRule(s"Невалидный JSON шаблона: $e"))
          tmpl   <- ZIO.serviceWithZIO[TemplateRepository](_.create(orgId, create))
        } yield Response.json(tmpl.toJson).status(Status.Created)
      }.catchAll(errorResponse)
    },

    // Обновить шаблон
    Method.PUT / "api" / "v1" / "templates" / long("id") -> handler { (id: Long, req: Request) =>
      withOrgId(req) { orgId =>
        for {
          body   <- req.body.asString.orDie
          create <- ZIO.fromEither(body.fromJson[CreateTemplate])
                      .mapError(e => InvalidRule(s"Невалидный JSON шаблона: $e"))
          result <- ZIO.serviceWithZIO[TemplateRepository](_.update(TemplateId(id), orgId, create))
        } yield result match
          case Some(tmpl) => Response.json(tmpl.toJson)
          case None       => Response.json("""{"error":"Template not found"}""").status(Status.NotFound)
      }.catchAll(errorResponse)
    }
  )

  private def withOrgId[R](req: Request)(f: OrganizationId => ZIO[R, NotificationError, Response]): ZIO[R, NotificationError, Response] =
    req.url.queryParams.get("orgId").flatMap(_.toLongOption) match
      case Some(id) => f(OrganizationId(id))
      case None     => ZIO.succeed(Response.json("""{"error":"orgId is required"}""").status(Status.BadRequest))

  private def errorResponse(err: NotificationError): UIO[Response] =
    val (status, message) = err match
      case TemplateNotFound(id)   => (Status.NotFound, s"Шаблон $id не найден")
      case InvalidRule(msg)       => (Status.BadRequest, msg)
      case DatabaseError(e)       => (Status.InternalServerError, s"Ошибка БД: ${e.getMessage}")
      case MissingOrganizationId  => (Status.BadRequest, "orgId обязателен")
      case other                  => (Status.InternalServerError, other.toString)
    ZIO.succeed(Response.json(s"""{"error":"$message"}""").status(status))
