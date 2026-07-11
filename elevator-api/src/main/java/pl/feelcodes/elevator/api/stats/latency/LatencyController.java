package pl.feelcodes.elevator.api.stats.latency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Read-only BI endpoint over the fact table (DuckDB views): how long the system takes to
 * process a passenger call (received -> done). {@code /api/stats/latency} is the
 * per-elevator + fleet-wide summary; {@code /api/stats/latency/calls} is the per-call detail.
 */
@RestController
@RequestMapping("/api/stats/latency")
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class LatencyController {

    private final LatencyService service;

    LatencyController(LatencyService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<LatencySummaryDto> summary() {
        return service.summary();
    }

    @GetMapping(value = "/calls", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<CallLatencyDto> calls() {
        return service.calls();
    }
}
