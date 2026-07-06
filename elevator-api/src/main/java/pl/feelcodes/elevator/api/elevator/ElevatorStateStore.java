package pl.feelcodes.elevator.api.elevator;

import org.springframework.stereotype.Component;
import pl.feelcodes.elevator.common.dto.ElevatorStateDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
class ElevatorStateStore {

    private final Map<String, ElevatorStateDto> latestByElevator = new ConcurrentHashMap<>();

    // Fan-out of every state change to live SSE subscribers. Multicast + autoCancel=false so the
    // sink survives clients coming and going; the buffer absorbs brief bursts (a fast engine emits
    // many moves per second). Emissions come from the single Kafka consumer thread, so serialized.
    private final Sinks.Many<ElevatorStateDto> changes =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void put(String elevatorName, ElevatorStateDto state) {
        if (elevatorName != null && state != null) {
            latestByElevator.put(elevatorName, state);
            changes.tryEmitNext(state);
        }
    }

    public Optional<ElevatorStateDto> get(String elevatorName) {
        return Optional.ofNullable(latestByElevator.get(elevatorName));
    }

    public Collection<ElevatorStateDto> all() {
        return latestByElevator.values();
    }

    /** Live stream of state changes, pushed the moment each move arrives. */
    public Flux<ElevatorStateDto> changes() {
        return changes.asFlux();
    }
}
