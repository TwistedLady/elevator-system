package pl.feelcodes.elevator.api.stats.conflicts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Read-only BI endpoint over the fact table (DuckDB views): passenger double-bookings —
 * any passenger served by two lifts over overlapping time windows. An empty list is the
 * healthy case (the one-lift-per-passenger invariant held).
 */
@RestController
@RequestMapping("/api/conflicts")
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class ConflictController {

    private final ConflictService service;

    ConflictController(ConflictService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<ConflictDto> all() {
        return service.all();
    }
}
