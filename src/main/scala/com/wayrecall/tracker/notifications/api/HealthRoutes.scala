package com.wayrecall.tracker.notifications.api

import zio.*
import zio.http.*
import zio.json.*

// ============================================================
// HEALTH ROUTES — /health endpoint
// ============================================================

object HealthRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> handler {
      Response.json("""{"status":"ok","service":"notification-service"}""")
    }
  )
