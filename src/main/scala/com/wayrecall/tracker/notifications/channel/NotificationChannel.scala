package com.wayrecall.tracker.notifications.channel

import zio.*
import com.wayrecall.tracker.notifications.domain.*

// ============================================================
// NOTIFICATION CHANNEL — Базовый трейт для каналов доставки
//
// Каждый канал реализует единственный метод send().
// DeliveryResult — результат: success / failed / rateLimited / throttled.
// ============================================================

trait NotificationChannel:
  /** Отправить уведомление конкретному получателю */
  def send(recipient: String, message: RenderedMessage): IO[NotificationError, DeliveryResult]

  /** Имя канала */
  def channelType: Channel
