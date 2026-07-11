package pl.feelcodes.elevator.api.stats;

import java.time.OffsetDateTime;

/**
 * One completed call with its end-to-end processing time (received -> done), read from the
 * {@code v_call} DuckDB view over the fact table. {@code updatedAt} is the fact file's
 * snapshot time, shared by every row.
 */
public record CallLatency(
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
