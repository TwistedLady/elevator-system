-- Analytics (BI) store — SEPARATE `postgres-stats` database (elevator_stats).
-- Maintained by the Spark orders-served job (elevator-bi / OrdersServedJob): one row per elevator =
-- how many times it reached an ordered floor (= completed orders), counted from the operational
-- `elevator` DB's order_status DONE rows and refreshed on an interval.
-- postgres-stats runs this from the postgres-stats-init ConfigMap on first init; the Spark sink also
-- issues CREATE TABLE IF NOT EXISTS, so an already-running stats DB needs no manual step.

CREATE TABLE IF NOT EXISTS elevator_orders_served (
  elevator_name VARCHAR(255) PRIMARY KEY,
  orders_served BIGINT       NOT NULL,
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
