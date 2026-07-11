package pl.feelcodes.elevator.api.stats.latency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.api.stats.CallLatency;
import pl.feelcodes.elevator.api.stats.LatencySummary;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;

import java.util.Comparator;

@Service
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class LatencyService {

    private final ParquetStatsReader reader;

    LatencyService(ParquetStatsReader reader) {
        this.reader = reader;
    }

    /** Per-elevator summaries slowest-first, with the fleet-wide ALL row always last. */
    Flux<LatencySummaryDto> summary() {
        return reader.latencySummary()
                .sort(Comparator
                        .comparing((LatencySummary s) -> s.elevatorName().equals("ALL"))
                        .thenComparing(s -> s.avgSeconds() == null ? -1.0 : s.avgSeconds(),
                                Comparator.reverseOrder()))
                .map(LatencyService::toDto);
    }

    /** Every completed call, slowest first. */
    Flux<CallLatencyDto> calls() {
        return reader.calls()
                .sort(Comparator.comparingDouble(CallLatency::processingSeconds).reversed())
                .map(LatencyService::toDto);
    }

    private static LatencySummaryDto toDto(LatencySummary s) {
        return new LatencySummaryDto(s.elevatorName(), s.calls(), s.avgSeconds(), s.minSeconds(),
                s.maxSeconds(), s.p50Seconds(), s.p95Seconds(), s.updatedAt());
    }

    private static CallLatencyDto toDto(CallLatency c) {
        return new CallLatencyDto(c.callId(), c.elevatorName(), c.floor(), c.orderId(), c.passengerId(),
                c.createdAt(), c.doneAt(), c.processingSeconds(), c.updatedAt());
    }
}
