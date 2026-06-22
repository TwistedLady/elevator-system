-- Durable, unbounded order dedup. The Kafka->Coordinator ingestion stream claims each order's
-- tag here before forwarding it; the primary key rejects duplicates (a re-sent tag conflicts and
-- is dropped). Replaces the Coordinator's old in-memory seen-tags set.

CREATE TABLE IF NOT EXISTS processed_orders (
  tag     VARCHAR(255) PRIMARY KEY,
  seen_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
