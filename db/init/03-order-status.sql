-- Order-status read-model (CQRS read-side) maintained by OrderStatusProjection.
-- One row per order tag = its lifecycle, derived from the Coordinator's events:
--   Accepted  -> status ACCEPTED
--   Completed -> status DONE   (the car reached the order's floor)
-- Lets the API answer "was this order processed?" by tag. The event_journal is the source of truth.

CREATE TABLE IF NOT EXISTS order_status (
  tag           VARCHAR(255) PRIMARY KEY,
  elevator_name VARCHAR(255) NOT NULL,
  floor         INT          NOT NULL,
  status        VARCHAR(16)  NOT NULL,   -- ACCEPTED | DONE
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
