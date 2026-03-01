-- ============================================================
-- Notification Service — V1 Initial Schema
-- База: tracker_notifications (PostgreSQL)
-- ============================================================

-- Правила уведомлений
CREATE TABLE IF NOT EXISTS notification_rules (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT NOT NULL,
    name              VARCHAR(255) NOT NULL,
    enabled           BOOLEAN DEFAULT true,
    event_type        VARCHAR(50) NOT NULL,
    conditions        JSONB DEFAULT '{}',
    apply_to_all      BOOLEAN DEFAULT false,
    vehicle_ids       BIGINT[] DEFAULT '{}',
    group_ids         BIGINT[] DEFAULT '{}',
    geozone_ids       BIGINT[] DEFAULT '{}',
    channels          TEXT[] NOT NULL,
    recipients        JSONB NOT NULL DEFAULT '{"userIds":[],"emails":[],"phones":[],"telegramChatIds":[],"webhookUrls":[]}',
    throttle_minutes  INT DEFAULT 15,
    template_id       BIGINT,
    custom_message    TEXT,
    schedule          JSONB,
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);

-- Индексы для быстрого поиска правил
CREATE INDEX idx_rules_org_id ON notification_rules(organization_id);
CREATE INDEX idx_rules_event_type ON notification_rules(organization_id, event_type) WHERE enabled = true;
CREATE INDEX idx_rules_vehicle_ids ON notification_rules USING GIN(vehicle_ids) WHERE enabled = true;

-- Автообновление updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_rules_updated_at
    BEFORE UPDATE ON notification_rules
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Шаблоны уведомлений
CREATE TABLE IF NOT EXISTS notification_templates (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT,  -- NULL = системный шаблон
    name              VARCHAR(255) NOT NULL,
    event_type        VARCHAR(50) NOT NULL,
    email_subject     TEXT,
    email_body        TEXT,
    sms_body          TEXT,
    push_title        TEXT,
    push_body         TEXT,
    telegram_body     TEXT,
    created_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_templates_org ON notification_templates(organization_id);
CREATE INDEX idx_templates_event ON notification_templates(event_type);

-- Системные шаблоны (defaults)
INSERT INTO notification_templates (organization_id, name, event_type, email_subject, email_body, sms_body, push_title, push_body, telegram_body)
VALUES
  -- Въезд в геозону
  (NULL, 'Въезд в геозону (системный)', 'geozone_enter',
   'Въезд в геозону: {{geozoneName}}',
   '<h3>Транспорт {{vehicleName}} въехал в геозону "{{geozoneName}}"</h3><p>Время: {{time}}</p><p>Координаты: {{lat}}, {{lon}}</p>',
   'Въезд в геозону {{geozoneName}}: {{vehicleName}} в {{time}}',
   'Въезд в геозону',
   '{{vehicleName}} → {{geozoneName}} в {{time}}',
   '🟢 <b>Въезд в геозону</b>\nТранспорт: {{vehicleName}}\nГеозона: {{geozoneName}}\nВремя: {{time}}'),

  -- Выезд из геозоны
  (NULL, 'Выезд из геозоны (системный)', 'geozone_leave',
   'Выезд из геозоны: {{geozoneName}}',
   '<h3>Транспорт {{vehicleName}} покинул геозону "{{geozoneName}}"</h3><p>Время: {{time}}</p><p>Координаты: {{lat}}, {{lon}}</p>',
   'Выезд из геозоны {{geozoneName}}: {{vehicleName}} в {{time}}',
   'Выезд из геозоны',
   '{{vehicleName}} ← {{geozoneName}} в {{time}}',
   '🔴 <b>Выезд из геозоны</b>\nТранспорт: {{vehicleName}}\nГеозона: {{geozoneName}}\nВремя: {{time}}'),

  -- Превышение скорости
  (NULL, 'Превышение скорости (системный)', 'speed_violation',
   'Превышение скорости: {{vehicleName}}',
   '<h3>Превышение скорости</h3><p>Транспорт: {{vehicleName}}</p><p>Скорость: {{speed}} км/ч (лимит: {{speedLimit}} км/ч)</p><p>Время: {{time}}</p>',
   'Превышение! {{vehicleName}}: {{speed}} км/ч (лимит {{speedLimit}})',
   'Превышение скорости',
   '{{vehicleName}}: {{speed}} км/ч',
   '⚠️ <b>Превышение скорости</b>\nТранспорт: {{vehicleName}}\nСкорость: {{speed}} км/ч\nЛимит: {{speedLimit}} км/ч\nВремя: {{time}}'),

  -- Устройство офлайн
  (NULL, 'Устройство офлайн (системный)', 'device_offline',
   'Устройство офлайн: {{vehicleName}}',
   '<h3>Устройство перешло в офлайн</h3><p>Транспорт: {{vehicleName}}</p><p>Последнее время связи: {{time}}</p>',
   'Офлайн: {{vehicleName}} с {{time}}',
   'Устройство офлайн',
   '{{vehicleName}} офлайн',
   '📴 <b>Устройство офлайн</b>\nТранспорт: {{vehicleName}}\nПоследняя связь: {{time}}');

-- История уведомлений (TimescaleDB hypertable если доступен)
CREATE TABLE IF NOT EXISTS notification_history (
    id                BIGSERIAL,
    rule_id           BIGINT,
    organization_id   BIGINT NOT NULL,
    event_type        VARCHAR(50) NOT NULL,
    vehicle_id        BIGINT NOT NULL,
    channel           VARCHAR(20) NOT NULL,
    recipient         TEXT NOT NULL,
    status            VARCHAR(20) NOT NULL,
    message           TEXT,
    error_message     TEXT,
    created_at        TIMESTAMPTZ DEFAULT now()
);

-- Индексы для истории
CREATE INDEX idx_history_org ON notification_history(organization_id, created_at DESC);
CREATE INDEX idx_history_vehicle ON notification_history(organization_id, vehicle_id, created_at DESC);
CREATE INDEX idx_history_status ON notification_history(status, created_at DESC);

-- Попытка создать TimescaleDB hypertable (если расширение установлено)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('notification_history', 'created_at', if_not_exists => TRUE);
        -- Retention policy: 90 дней
        PERFORM add_retention_policy('notification_history', INTERVAL '90 days', if_not_exists => TRUE);
    END IF;
END $$;
