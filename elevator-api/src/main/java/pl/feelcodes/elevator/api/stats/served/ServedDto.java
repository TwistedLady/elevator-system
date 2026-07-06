package pl.feelcodes.elevator.api.stats.served;

import java.time.OffsetDateTime;

public record ServedDto(
        String elevatorName,
        Long ordersServed,
        OffsetDateTime updatedAt) {
}
