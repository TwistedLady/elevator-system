package pl.feelcodes.elevator.api.call;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// One action for the consoles: generate a burst of random calls server-side and hand back
// their ids. The consoles then poll /api/simulate/status for the DONE/PROGRESS/pending split
// instead of firing thousands of requests themselves.
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
        String runId = UUID.randomUUID().toString().substring(0, 8);

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

    @PostMapping("/status")
    Mono<SimStatusResponse> status(@RequestBody SimStatusRequest request) {
        List<String> ids = request.ids();
        if (ids == null || ids.isEmpty()) {
            return Mono.just(new SimStatusResponse(0, 0, 0, 0));
        }
        int total = ids.size();
        return callStatusRepository.findAllById(ids)
                .reduce(new int[2], (acc, entity) -> {
                    if ("DONE".equals(entity.getStatus())) {
                        acc[0]++;
                    } else if ("PROGRESS".equals(entity.getStatus())) {
                        acc[1]++;
                    }
                    return acc;
                })
                .map(acc -> {
                    int done = acc[0];
                    int progress = acc[1];
                    int pending = Math.max(0, total - done - progress);
                    return new SimStatusResponse(total, done, progress, pending);
                });
    }

    private static int clamp(int requested) {
        return Math.max(1, Math.min(requested, MAX_COUNT));
    }

    private record CallSpec(String id, String elevator, int floor) {
    }
}
