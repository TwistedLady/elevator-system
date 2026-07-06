-- BI read-model maintained by the Spark mileage job (elevator-bi).
-- One row per elevator = its cumulative MILEAGE: total floors travelled = Σ |floor - prevFloor|
-- over the elevator-state stream. Upserted each Spark micro-batch (absolute totals, not deltas).
--
-- Two ways this table appears:
--   * fresh cluster  -> postgres runs this file from the postgres-init ConfigMap on first init
--                       (regenerate that ConfigMap if you add/edit files here).
--   * running cluster -> the Spark sink issues CREATE TABLE IF NOT EXISTS on its first batch,
--                        so no manual step is needed to start the job on an existing DB.

CREATE TABLE IF NOT EXISTS elevator_mileage (
  elevator_name    VARCHAR(255) PRIMARY KEY,
  floors_travelled BIGINT       NOT NULL,
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
