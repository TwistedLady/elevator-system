-- Read-model (CQRS read-side) maintained by ElevatorStateProjection.
-- One row per elevator = its current state, upserted on each Controller.ElevatorStateUpdated event.
-- This is the queryable view; the event_journal (01-pekko-r2dbc.sql) is the source of truth.

CREATE TABLE IF NOT EXISTS elevator_state_view (
  elevator_name  VARCHAR(255) PRIMARY KEY,
  floor          INT          NOT NULL,
  direction      VARCHAR(8)   NOT NULL,   -- Up | Down
  motion         VARCHAR(8)   NOT NULL,   -- Moving | Stopped
  last_order_tag VARCHAR(255),            -- tag of the order being served on the last update
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
