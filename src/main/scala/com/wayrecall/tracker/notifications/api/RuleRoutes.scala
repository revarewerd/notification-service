package com.wayrecall.tracker.notifications.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.RuleRepository

// ============================================================
// RULE ROUTES — CRUD правил уведомлений
//
// GET    /api/v1/rules?orgId=...       — список правил
// GET    /api/v1/rules/:id?orgId=...   — получить правило
// POST   /api/v1/rules?orgId=...       — создать правило
// PUT    /api/v1/rules/:id?orgId=...   — обновить правило
// DELETE /api/v1/rules/:id?orgId=...   — удалить правило
// PATCH  /api/v1/rules/:id/toggle?orgId=... — вкл/выкл
// ============================================================

object RuleRoutes:

  def routes: Routes[RuleRepository, Nothing] = Routes(
    // Получить все правила организации
    Method.GET / "api" / "v1" / "rules" -> handler { (req: Request) =>
      withOrgId(req) { orgId =>
        ZIO.serviceWithZIO[RuleRepository](_.findByOrganization(orgId))
          .map(rules => Response.json(rules.toJson))
      }.catchAll(errorResponse)
    },

    // Получить правило по ID
    Method.GET / "api" / "v1" / "rules" / long("id") -> handler { (id: Long, req: Request) =>
      withOrgId(req) { orgId =>
        ZIO.serviceWithZIO[RuleRepository](_.findById(RuleId(id), orgId))
          .map {
            case Some(rule) => Response.json(rule.toJson)
            case None       => Response.json("""{"error":"Rule not found"}""").status(Status.NotFound)
          }
      }.catchAll(errorResponse)
    },

    // Создать правило
    Method.POST / "api" / "v1" / "rules" -> handler { (req: Request) =>
      withOrgId(req) { orgId =>
        for {
          body   <- req.body.asString.orDie
          create <- ZIO.fromEither(body.fromJson[CreateRule])
                      .mapError(e => InvalidRule(s"Невалидный JSON: $e"))
          rule   <- ZIO.serviceWithZIO[RuleRepository](_.create(orgId, create))
        } yield Response.json(rule.toJson).status(Status.Created)
      }.catchAll(errorResponse)
    },

    // Обновить правило
    Method.PUT / "api" / "v1" / "rules" / long("id") -> handler { (id: Long, req: Request) =>
      withOrgId(req) { orgId =>
        for {
          body   <- req.body.asString.orDie
          create <- ZIO.fromEither(body.fromJson[CreateRule])
                      .mapError(e => InvalidRule(s"Невалидный JSON: $e"))
          result <- ZIO.serviceWithZIO[RuleRepository](_.update(RuleId(id), orgId, create))
        } yield result match
          case Some(rule) => Response.json(rule.toJson)
          case None       => Response.json("""{"error":"Rule not found"}""").status(Status.NotFound)
      }.catchAll(errorResponse)
    },

    // Удалить правило
    Method.DELETE / "api" / "v1" / "rules" / long("id") -> handler { (id: Long, req: Request) =>
      withOrgId(req) { orgId =>
        ZIO.serviceWithZIO[RuleRepository](_.delete(RuleId(id), orgId))
          .map(deleted =>
            if deleted then Response.json("""{"deleted":true}""")
            else Response.json("""{"error":"Rule not found"}""").status(Status.NotFound)
          )
      }.catchAll(errorResponse)
    },

    // Включить/выключить правило
    Method.PATCH / "api" / "v1" / "rules" / long("id") / "toggle" -> handler { (id: Long, req: Request) =>
      withOrgId(req) { orgId =>
        ZIO.serviceWithZIO[RuleRepository](_.toggle(RuleId(id), orgId))
          .map {
            case Some(rule) => Response.json(rule.toJson)
            case None       => Response.json("""{"error":"Rule not found"}""").status(Status.NotFound)
          }
      }.catchAll(errorResponse)
    }
  )

  // === Вспомогательные функции ===

  /** Извлечь orgId из query параметра */
  private def withOrgId[R](req: Request)(f: OrganizationId => ZIO[R, NotificationError, Response]): ZIO[R, NotificationError, Response] =
    req.url.queryParams.get("orgId").flatMap(_.toLongOption) match
      case Some(id) => f(OrganizationId(id))
      case None     => ZIO.succeed(Response.json("""{"error":"orgId is required"}""").status(Status.BadRequest))

  /** Преобразовать ошибку в HTTP ответ */
  private def errorResponse(err: NotificationError): UIO[Response] =
    val (status, message) = err match
      case RuleNotFound(id)       => (Status.NotFound, s"Правило $id не найдено")
      case InvalidRule(msg)       => (Status.BadRequest, msg)
      case DatabaseError(e)       => (Status.InternalServerError, s"Ошибка БД: ${e.getMessage}")
      case MissingOrganizationId  => (Status.BadRequest, "orgId обязателен")
      case OrganizationMismatch   => (Status.Forbidden, "Доступ запрещён")
      case other                  => (Status.InternalServerError, other.toString)
    ZIO.succeed(Response.json(s"""{"error":"$message"}""").status(status))
