package pl.feelcodes.elevator.api.elevator;

import org.springframework.stereotype.Component;
import pl.feelcodes.elevator.common.dto.ElevatorStateDto;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the latest known {@link ElevatorStateDto} per elevator, fed by {@link ElevatorStateConsumer}
 * from the Kafka elevator-state topic. Read by {@link ElevatorController}.
 */
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

    /** Latest state for every elevator seen so far. */
    public Collection<ElevatorStateDto> all() {
        return latestByElevator.values();
    }
}
