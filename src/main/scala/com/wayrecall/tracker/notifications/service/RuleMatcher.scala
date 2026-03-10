package com.wayrecall.tracker.notifications.service

import zio.*
import com.wayrecall.tracker.notifications.domain.*
import com.wayrecall.tracker.notifications.domain.NotificationError.*
import com.wayrecall.tracker.notifications.repository.RuleRepository

// ============================================================
// RULE MATCHER — Поиск правил, соответствующих событию
//
// Логика: по типу события + vehicleId + orgId → список правил.
// Дополнительная фильтрация по conditions (speed, geozone и т.д.)
// ============================================================

trait RuleMatcher:
  /** Найти все правила, подходящие для данного события */
  def findMatchingRules(event: NotificationEvent): IO[NotificationError, List[NotificationRule]]

object RuleMatcher:

  val live: ZLayer[RuleRepository, Nothing, RuleMatcher] =
    ZLayer.fromFunction((repo: RuleRepository) => Live(repo))

  private case class Live(repo: RuleRepository) extends RuleMatcher:

    override def findMatchingRules(event: NotificationEvent): IO[NotificationError, List[NotificationRule]] =
      import zio.json.*
      val eventTypeStr = event.eventType.toJson.stripPrefix("\"").stripSuffix("\"")
      for {
        // Получаем правила из БД (уже фильтрованные по org, event_type, vehicle)
        rules    <- repo.findMatchingRules(event.organizationId, eventTypeStr, event.vehicleId.value)
        // Дополнительная фильтрация по conditions (если заданы)
        filtered  = rules.filter(rule => matchesConditions(rule, event))
        _        <- ZIO.logDebug(s"Правила: найдено ${rules.size} правил, после фильтрации=${filtered.size} для события ${event.eventType} " +
                      s"vehicle=${event.vehicleId.value} org=${event.organizationId.value}")
        _        <- ZIO.when(rules.nonEmpty && filtered.isEmpty)(ZIO.logDebug(s"Правила: все ${rules.size} правил отфильтрованы conditions для ${event.eventType}"))
      } yield filtered

    /** Проверка дополнительных условий правила */
    private def matchesConditions(rule: NotificationRule, event: NotificationEvent): Boolean =
      val cond = rule.conditions
      // Проверяем порог скорости (если задан)
      val speedOk = cond.speedThreshold match
        case Some(threshold) =>
          event.payload.get("speed").flatMap(_.toDoubleOption).exists(_ >= threshold)
        case None => true

      // Проверяем геозону (если правило привязано к конкретным геозонам)
      val geozoneOk =
        if rule.geozoneIds.isEmpty then true
        else event.payload.get("geozoneId").flatMap(_.toLongOption).exists(rule.geozoneIds.contains)

      // Проверяем действие геозоны (enter/leave)
      val geozoneActionOk = cond.geozoneAction match
        case Some("enter") => event.eventType == EventType.GeozoneEnter
        case Some("leave") => event.eventType == EventType.GeozoneLeave
        case _             => true

      speedOk && geozoneOk && geozoneActionOk
