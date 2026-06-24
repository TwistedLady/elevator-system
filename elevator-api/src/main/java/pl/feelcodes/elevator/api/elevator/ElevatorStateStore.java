package pl.feelcodes.elevator.api.elevator;

import org.springframework.stereotype.Component;
import pl.feelcodes.elevator.common.dto.ElevatorStateDto;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
class ElevatorStateStore {

    private final Map<String, ElevatorStateDto> latestByElevator = new ConcurrentHashMap<>();

    public void put(String elevatorName, ElevatorStateDto state) {
        if (elevatorName != null && state != null) {
            latestByElevator.put(elevatorName, state);
        }
    }

    public Optional<ElevatorStateDto> get(String elevatorName) {
        return Optional.ofNullable(latestByElevator.get(elevatorName));
    }

    public Collection<ElevatorStateDto> all() {
        return latestByElevator.values();
    }
}
