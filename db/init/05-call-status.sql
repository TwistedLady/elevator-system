-- Call-status read-model (CQRS read-side) maintained by CallStatusProjection.
-- One row per call = its lifecycle, derived from the Coordinator's events:
--   CallReceived -> status PROGRESS, created_at set
--   CallAssigned -> order_id set (the order this call was grouped into)
--   CallDone     -> status DONE,     done_at set
-- Lets the API answer "what's this call's status?" by id. The event_journal is the source of truth.

CREATE TABLE IF NOT EXISTS call_status (
  call_id       VARCHAR(255) PRIMARY KEY,
  elevator_name VARCHAR(255) NOT NULL,
  floor         INT          NOT NULL,
  order_id      VARCHAR(255),
  status        VARCHAR(16)  NOT NULL,        -- PROGRESS | DONE
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  done_at       TIMESTAMPTZ
);
