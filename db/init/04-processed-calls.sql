-- Durable, unbounded call dedup. The Kafka->Coordinator ingestion stream marks each call's id
-- here after forwarding it; the primary key rejects duplicates (a re-sent id conflicts and is
-- dropped). Used by CallDedup.

CREATE TABLE IF NOT EXISTS processed_calls (
  call_id VARCHAR(255) PRIMARY KEY,
  seen_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
