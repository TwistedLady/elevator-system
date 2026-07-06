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
     * Live state as Server-Sent Events: the latest snapshot of every elevator, pushed every 500ms.
     * Lets the console (and any client) follow movement without polling. One SSE event per elevator.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ElevatorStateDto> stream() {
        return Flux.interval(Duration.ofMillis(500))
                .flatMapIterable(tick -> store.all());
    }
}
