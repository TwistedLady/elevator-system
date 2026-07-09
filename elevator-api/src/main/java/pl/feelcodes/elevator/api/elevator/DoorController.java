package pl.feelcodes.elevator.api.elevator;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.common.dto.DoorStateDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
@RequestMapping("/api/door")
class DoorController {

    private final DoorStateStore store;

    DoorController(DoorStateStore store) {
        this.store = store;
    }

    @GetMapping
    public Flux<DoorStateDto> all() {
        return Flux.fromIterable(store.all());
    }

    @GetMapping("/{name}")
    public Mono<ResponseEntity<DoorStateDto>> latest(@PathVariable("name") String name) {
        return Mono.justOrEmpty(store.get(name))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DoorStateDto> stream() {
        Flux<DoorStateDto> snapshots = Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
                .flatMapIterable(tick -> store.all());
        return Flux.merge(store.changes(), snapshots);
    }
}
