package pl.feelcodes.elevator.api.elevator;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.common.dto.ElevatorStateDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
@RequestMapping("/api/elevator")
class ElevatorController {

    private final ElevatorStateStore store;

    ElevatorController(ElevatorStateStore store) {
        this.store = store;
    }

    @GetMapping
    public Flux<ElevatorStateDto> all() {
        return Flux.fromIterable(store.all());
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<ElevatorStateDto>> latest(@PathVariable("name") String name) {
        return Mono.justOrEmpty(store.get(name))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Live state as Server-Sent Events. Each move is pushed the moment it arrives (engine-paced),
     * not on a fixed timer, so the client follows movement with no added lag. A slow snapshot tick
     * (immediately, then every 10s) seeds a new subscriber with current positions and keeps idle
     * connections warm. One SSE event per elevator.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ElevatorStateDto> stream() {
        Flux<ElevatorStateDto> snapshots = Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
                .flatMapIterable(tick -> store.all());
        return Flux.merge(store.changes(), snapshots);
    }
}
