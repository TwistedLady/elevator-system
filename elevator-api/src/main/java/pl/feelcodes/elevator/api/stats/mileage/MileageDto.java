package pl.feelcodes.elevator.api.stats.mileage;

import java.time.OffsetDateTime;

public record MileageDto(
        String elevatorName,
        Long floorsTravelled,
        OffsetDateTime updatedAt) {
}
