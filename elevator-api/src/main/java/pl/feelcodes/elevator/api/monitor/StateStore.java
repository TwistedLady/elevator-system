package pl.feelcodes.elevator.api.monitor;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the latest known state JSON per elevator, fed by {@link StateConsumer}
 * from the Kafka elevator-state topic. Read by the monitor endpoint.
 */
@Component
class StateStore {

    private final Map<String, String> latestByElevator = new ConcurrentHashMap<>();

    public void put(String elevatorName, String stateJson) {
        if (elevatorName != null && stateJson != null) {
            latestByElevator.put(elevatorName, stateJson);
        }
    }

    public Optional<String> get(String elevatorName) {
        return Optional.ofNullable(latestByElevator.get(elevatorName));
    }

    /** Latest state JSON for every elevator seen so far. */
    public Collection<String> all() {
        return latestByElevator.values();
    }
}
