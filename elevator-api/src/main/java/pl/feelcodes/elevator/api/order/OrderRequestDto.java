package pl.feelcodes.elevator.api.order;

import java.util.UUID;

@ValidOrder
record OrderRequestDto(String tag, String elevatorName, Integer floor) {

    OrderRequestDto withTagIfAbsent() {
        return tag != null ? this : new OrderRequestDto(UUID.randomUUID().toString(), elevatorName, floor);
    }
}
