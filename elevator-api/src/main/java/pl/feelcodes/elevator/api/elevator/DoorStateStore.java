package pl.feelcodes.elevator.api.elevator;

import org.springframework.stereotype.Component;
import pl.feelcodes.elevator.common.dto.DoorStateDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
class DoorStateStore {

    private final Map<String, DoorStateDto> latestByElevator = new ConcurrentHashMap<>();

    private final Sinks.Many<DoorStateDto> changes =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void put(String elevatorName, DoorStateDto state) {
        if (elevatorName != null && state != null) {
            latestByElevator.put(elevatorName, state);
            changes.tryEmitNext(state);
        }
    }

    public Optional<DoorStateDto> get(String elevatorName) {
        return Optional.ofNullable(latestByElevator.get(elevatorName));
    }

    public Collection<DoorStateDto> all() {
        return latestByElevator.values();
    }

    public Flux<DoorStateDto> changes() {
        return changes.asFlux();
    }
}
