package pl.feelcodes.elevator.api.mileage;

import java.time.OffsetDateTime;

public record MileageDto(
        String elevatorName,
        Long floorsTravelled,
        OffsetDateTime updatedAt) {
}
