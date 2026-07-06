package pl.feelcodes.elevator.api.served;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only BI endpoint over the elevator_orders_served table (maintained by the elevator-bi
 * OrdersServedJob): how many times each elevator reached an ordered floor (= completed orders).
 */
@RestController
@RequestMapping("/api/served")
class ServedController {

    private final ServedService service;

    ServedController(ServedService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<ServedDto> all() {
        return service.all();
    }

    @GetMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ServedDto>> byElevator(@PathVariable("name") String name) {
        return service.byElevator(name)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
