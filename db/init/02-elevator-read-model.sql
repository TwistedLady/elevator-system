-- Read-model (CQRS read-side) maintained by ElevatorStateProjection: one row per elevator, its
-- current state, upserted on each Controller.ElevatorStateUpdated event. Source of truth is the
-- event_journal (01-pekko-r2dbc.sql). direction Up|Down, motion Moving|Stopped.

CREATE TABLE IF NOT EXISTS elevator_state_view (
  elevator_name  VARCHAR(255) PRIMARY KEY,
  floor          INT          NOT NULL,
  direction      VARCHAR(8)   NOT NULL,
  motion         VARCHAR(8)   NOT NULL,
  last_order_tag VARCHAR(255),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
