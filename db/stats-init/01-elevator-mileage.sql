-- Analytics (BI) store — SEPARATE `postgres-stats` database (elevator_stats), isolated from the
-- operational `elevator` DB (Pekko journal + order read-models).
--
-- Maintained by the Spark mileage job (elevator-bi / MileageJob): one row per elevator = its
-- cumulative MILEAGE (total floors travelled = Σ |floor - prevFloor|), upserted each micro-batch.
-- postgres-stats runs this from the postgres-stats-init ConfigMap on first init; the Spark sink also
-- issues CREATE TABLE IF NOT EXISTS, so an already-running stats DB needs no manual step.

CREATE TABLE IF NOT EXISTS elevator_mileage (
  elevator_name    VARCHAR(255) PRIMARY KEY,
  floors_travelled BIGINT       NOT NULL,
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
