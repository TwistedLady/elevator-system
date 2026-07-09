package pl.feelcodes.elevator.api.call;

import java.util.UUID;

@ValidCall
record CallRequestDto(String id, String elevatorName, Integer floor, String passengerId) {

    CallRequestDto withIdIfAbsent() {
        return id != null ? this : new CallRequestDto(UUID.randomUUID().toString(), elevatorName, floor, passengerId);
    }
}
