package pl.feelcodes.elevator.api.call;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// One action for the consoles: generate a burst of random calls server-side and hand back their
// run id and ids. Both consoles then poll GET /api/simulate/progress?runId=..&size=.. for a single
// rolled-up view of the run (source for the progress bar), instead of firing the burst themselves.
@Slf4j
@RestController
@RequestMapping("/api/simulate")
class SimulationController {

    static final int DEFAULT_COUNT = 10_000;
    static final int MAX_COUNT = 10_000;
    private static final int PUBLISH_CONCURRENCY = 256;

    private final CallService callService;
    private final CallStatusRepository callStatusRepository;
    private final ElevatorLimits limits;

    SimulationController(CallService callService,
                         CallStatusRepository callStatusRepository,
                         ElevatorLimits limits) {
        this.callService = callService;
        this.callStatusRepository = callStatusRepository;
        this.limits = limits;
    }

    @PostMapping
    Mono<SimulateResponse> simulate(@RequestParam(value = "count", required = false) Integer count) {
        int n = clamp(count == null ? DEFAULT_COUNT : count);
        List<String> elevators = limits.getElevators();
        int maxFloor = limits.getMaxFloor();
        String runId = java.util.UUID.randomUUID().toString().substring(0, 8);

        List<CallSpec> specs = new ArrayList<>(n);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            String elevator = elevators.get(rnd.nextInt(elevators.size()));
            int floor = 1 + rnd.nextInt(maxFloor);
            specs.add(new CallSpec("sim-" + runId + "-" + i, elevator, floor));
        }
        log.info("[simulate   ] run {} -> {} calls across {} elevators (maxFloor {})",
                runId, n, elevators.size(), maxFloor);

        return Flux.fromIterable(specs)
                .flatMap(spec -> callService.call(spec.id(), spec.elevator(), spec.floor(), null)
                        .thenReturn(spec.id()), PUBLISH_CONCURRENCY)
                .collectList()
                .map(ids -> new SimulateResponse(runId, ids.size(), ids));
    }

    @GetMapping("/progress")
    Mono<SimProgress> progress(@RequestParam("runId") String runId,
                               @RequestParam(value = "size", defaultValue = "0") int size) {
        String prefix = "sim-" + runId + "-%";
        return callStatusRepository.findByCallIdPrefix(prefix)
                .collectList()
                .map(rows -> {
                    long calls = rows.size();
                    long doneCalls = rows.stream().filter(r -> "DONE".equals(r.getStatus())).count();
                    long orders = rows.stream()
                            .map(CallStatusEntity::getOrderId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .count();
                    OffsetDateTime firstCall = rows.stream()
                            .map(CallStatusEntity::getCreatedAt)
                            .filter(Objects::nonNull)
                            .min(Comparator.naturalOrder())
                            .orElse(null);
                    OffsetDateTime lastDone = rows.stream()
                            .map(CallStatusEntity::getDoneAt)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
                    return new SimProgress(size, calls, orders, doneCalls, firstCall, lastDone);
                });
    }

    private static int clamp(int requested) {
        return Math.max(1, Math.min(requested, MAX_COUNT));
    }

    private record CallSpec(String id, String elevator, int floor) {
    }
}
