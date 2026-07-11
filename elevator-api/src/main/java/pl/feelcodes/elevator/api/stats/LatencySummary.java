package pl.feelcodes.elevator.api.stats;

import java.time.OffsetDateTime;

/**
 * Call processing-time summary for one elevator (or the fleet-wide {@code "ALL"} row),
 * from the {@code v_latency_summary} DuckDB view. Metrics are boxed because they are null
 * when {@code calls} is zero (no completed calls yet).
 */
public record LatencySummary(
        String elevatorName,
        long calls,
        Double avgSeconds,
        Double minSeconds,
        Double maxSeconds,
        Double p50Seconds,
        Double p95Seconds,
        OffsetDateTime updatedAt) {
}
