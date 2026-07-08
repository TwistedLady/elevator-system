package pl.feelcodes.elevator.api.call;

import java.time.OffsetDateTime;

public record CallStatusDto(
        String id,
        String elevatorId,
        Integer floor,
        String orderId,
        OffsetDateTime createDateTime,
        OffsetDateTime doneDateTime,
        String status) {
}
