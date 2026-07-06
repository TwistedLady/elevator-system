package pl.feelcodes.elevator.api.config;

import java.util.List;

public record ElevatorConfigDto(int maxFloor, List<String> elevators, boolean biEnabled) {
}
