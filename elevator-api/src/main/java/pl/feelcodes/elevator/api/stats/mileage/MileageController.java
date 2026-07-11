package pl.feelcodes.elevator.api.stats.mileage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only BI endpoint over the Parquet BI read-model (produced by the elevator-bi Spark job, read
 * via DuckDB): each elevator's cumulative floors travelled.
 */
@RestController
@RequestMapping("/api/stats/mileage")
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class MileageController {

    private final MileageService service;

    MileageController(MileageService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<MileageDto> all() {
        return service.all();
    }

    @GetMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<MileageDto>> byElevator(@PathVariable("name") String name) {
        return service.byElevator(name)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
