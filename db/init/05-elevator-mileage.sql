-- BI read-model maintained by the Spark mileage job (elevator-bi / MileageJob).
-- One row per elevator = its cumulative MILEAGE: total floors travelled = Σ |floor - prevFloor|
-- over the elevator-state stream. Upserted each Spark micro-batch (absolute totals, not deltas).
--
-- Fresh cluster -> postgres runs this from the postgres-init ConfigMap on first init.
-- Running cluster -> the Spark sink issues CREATE TABLE IF NOT EXISTS, so no manual step is needed.

CREATE TABLE IF NOT EXISTS elevator_mileage (
  elevator_name    VARCHAR(255) PRIMARY KEY,
  floors_travelled BIGINT       NOT NULL,
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
