package pl.feelcodes.elevator.api.stats.conflicts;

import java.time.OffsetDateTime;

public record ConflictDto(
        String passengerId,
        String elevatorA,
        String orderA,
        String elevatorB,
        String orderB,
        OffsetDateTime overlapStart,
        OffsetDateTime overlapEnd,
        OffsetDateTime updatedAt) {
}
