package pl.feelcodes.elevator.api.call;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import pl.feelcodes.elevator.sim.CallSender;
import pl.feelcodes.elevator.sim.SimRun;
import pl.feelcodes.elevator.sim.Simulator;
import reactor.core.publisher.Mono;

// One action for the consoles: run the elevator-sim engine server-side (a fixed burst of random
// calls) and hand back the run id and ids. Both consoles then poll
// GET /api/simulate/progress?runId=..&size=.. for a single rolled-up view of the run (source for
// the progress bar), instead of firing the burst themselves.
@Slf4j
@RestController
@RequestMapping("/api/simulate")
class SimulationController {

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

    // The scenario is fixed (elevator-sim fires Simulator.Riders calls), so `count` is accepted for
    // console compatibility but ignored.
    @PostMapping
    Mono<SimulateResponse> simulate(@RequestParam(value = "count", required = false) Integer count) {
        CallSender sender = spec ->
                callService.call(spec.id(), spec.elevator(), spec.floor(), null).subscribe();
        SimRun run = new Simulator(sender, limits.getElevators(), limits.getMaxFloor()).run();
        log.info("[simulate   ] run {} -> {} calls across {} elevators (maxFloor {})",
                run.runId(), run.ids().size(), limits.getElevators().size(), limits.getMaxFloor());
        return Mono.just(new SimulateResponse(run.runId(), run.ids().size(), run.ids()));
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
}
