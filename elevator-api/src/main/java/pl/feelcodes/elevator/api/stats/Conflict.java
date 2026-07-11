package pl.feelcodes.elevator.api.stats;

import java.time.OffsetDateTime;

/**
 * A passenger double-booking: the same passenger was being served by two different lifts
 * over overlapping time windows. Read from the {@code v_conflicts} DuckDB view. An empty
 * result is the healthy case (the one-lift-per-passenger invariant held).
 */
public record Conflict(
        String passengerId,
        String elevatorA,
        String orderA,
        String elevatorB,
        String orderB,
        OffsetDateTime overlapStart,
        OffsetDateTime overlapEnd,
        OffsetDateTime updatedAt) {
}
