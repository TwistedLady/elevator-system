package pl.feelcodes.elevator.api.elevator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.common.dto.ElevatorStateDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
}
