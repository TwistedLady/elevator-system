package pl.feelcodes.elevator.api.stats.latency;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.feelcodes.elevator.api.stats.CallLatency;
import pl.feelcodes.elevator.api.stats.LatencySummary;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class LatencyServiceTest {

    private final ParquetStatsReader reader = Mockito.mock(ParquetStatsReader.class);
    private final LatencyService service = new LatencyService(reader);

    private static LatencySummary summary(String name, long calls, Double avg) {
        return new LatencySummary(name, calls, avg, avg, avg, avg, avg, OffsetDateTime.now());
    }

    private static CallLatency call(String id, double seconds) {
        return new CallLatency(id, "e1", 3, "o1", "alice",
                OffsetDateTime.now(), OffsetDateTime.now(), seconds, OffsetDateTime.now());
    }

    @Test
    void summarySortsBySlowestAvgWithAllRowLast() {
        when(reader.latencySummary()).thenReturn(Flux.just(
                summary("e1", 2, 3.25), summary("ALL", 3, 3.5), summary("e2", 1, 8.0)));

        List<LatencySummaryDto> result = service.summary().collectList().block();

        assertThat(result).extracting(LatencySummaryDto::elevatorName).containsExactly("e2", "e1", "ALL");
    }

    @Test
    void callsSortSlowestFirst() {
        when(reader.calls()).thenReturn(Flux.just(call("c1", 2.5), call("c2", 4.0), call("c3", 1.0)));

        List<CallLatencyDto> result = service.calls().collectList().block();

        assertThat(result).extracting(CallLatencyDto::callId).containsExactly("c2", "c1", "c3");
        assertThat(result).extracting(CallLatencyDto::processingSeconds).containsExactly(4.0, 2.5, 1.0);
    }
}
