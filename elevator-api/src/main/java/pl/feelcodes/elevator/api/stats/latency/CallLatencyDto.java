package pl.feelcodes.elevator.api.stats.latency;

import java.time.OffsetDateTime;

public record CallLatencyDto(
        String callId,
        String elevatorName,
        int floor,
        String orderId,
        String passengerId,
        OffsetDateTime createdAt,
        OffsetDateTime doneAt,
        double processingSeconds,
        OffsetDateTime updatedAt) {
}
