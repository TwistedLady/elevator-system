-- Call-status read-model (CQRS read-side) maintained by CallStatusProjection, derived from the
-- Coordinator's events (one row per call):
--   CallReceived -> PROGRESS, created_at   CallAssigned -> order_id set   CallDone -> DONE, done_at
-- Lets the API answer a call's status by id. The event_journal is the source of truth.

CREATE TABLE IF NOT EXISTS call_status (
  call_id       VARCHAR(255) PRIMARY KEY,
  elevator_name VARCHAR(255) NOT NULL,
  floor         INT          NOT NULL,
  order_id      VARCHAR(255),
  status        VARCHAR(16)  NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  done_at       TIMESTAMPTZ
);
