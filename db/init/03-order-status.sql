-- Order-status read-model (CQRS read-side) maintained by OrderStatusProjection.
-- One row per order = its lifecycle, derived from the Manager's events:
--   OrderCreated -> status PROGRESS, created_at set
--   OrderDone    -> status DONE,     done_at set (the car reached the order's floor)
-- The event_journal is the source of truth.

CREATE TABLE IF NOT EXISTS order_status (
  order_id      VARCHAR(255) PRIMARY KEY,
  elevator_name VARCHAR(255) NOT NULL,
  floor         INT          NOT NULL,
  status        VARCHAR(16)  NOT NULL,        -- PROGRESS | DONE
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  done_at       TIMESTAMPTZ
);
