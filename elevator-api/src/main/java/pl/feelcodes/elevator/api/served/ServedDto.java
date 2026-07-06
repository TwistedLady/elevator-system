package pl.feelcodes.elevator.api.served;

import java.time.OffsetDateTime;

public record ServedDto(
        String elevatorName,
        Long ordersServed,
        OffsetDateTime updatedAt) {
}
