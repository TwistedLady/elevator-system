package pl.feelcodes.elevator.api.orderstatus;

import java.time.OffsetDateTime;

/**
 * Lifecycle of one order, read from the {@code order_status} read-model.
 * {@code status} is PROGRESS (accepted, on the way) or DONE (the car reached the floor).
 * {@code doneDateTime} is null while still in progress.
 */
public record OrderStatusDto(
        String tag,
        String elevatorId,
        Integer floor,
        OffsetDateTime createDateTime,
        OffsetDateTime doneDateTime,
        String status) {
}
