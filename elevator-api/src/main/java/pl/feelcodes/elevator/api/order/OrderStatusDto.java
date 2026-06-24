package pl.feelcodes.elevator.api.order;

import java.time.OffsetDateTime;

public record OrderStatusDto(
        String tag,
        String elevatorId,
        Integer floor,
        OffsetDateTime createDateTime,
        OffsetDateTime doneDateTime,
        String status) {
}
