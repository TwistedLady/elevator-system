-- BI read-model maintained by the Spark orders-served job (elevator-bi / OrdersServedJob).
-- One row per elevator = how many times it reached an ordered floor (= completed orders),
-- counted from order_status DONE rows and refreshed on an interval.
--
-- Fresh cluster -> postgres runs this from the postgres-init ConfigMap on first init.
-- Running cluster -> the Spark sink issues CREATE TABLE IF NOT EXISTS, so no manual step is needed.

CREATE TABLE IF NOT EXISTS elevator_orders_served (
  elevator_name VARCHAR(255) PRIMARY KEY,
  orders_served BIGINT       NOT NULL,
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
