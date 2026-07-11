package pl.feelcodes.elevator.api.stats.latency;

import java.time.OffsetDateTime;

public record LatencySummaryDto(
        String elevatorName,
        long calls,
        Double avgSeconds,
        Double minSeconds,
        Double maxSeconds,
        Double p50Seconds,
        Double p95Seconds,
        OffsetDateTime updatedAt) {
}
