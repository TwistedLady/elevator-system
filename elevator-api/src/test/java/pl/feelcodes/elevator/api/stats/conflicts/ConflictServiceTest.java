package pl.feelcodes.elevator.api.stats.conflicts;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.feelcodes.elevator.api.stats.Conflict;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ConflictServiceTest {

    private final ParquetStatsReader reader = Mockito.mock(ParquetStatsReader.class);
    private final ConflictService service = new ConflictService(reader);

    private static Conflict conflict(String passenger, int overlapSecond) {
        OffsetDateTime start = OffsetDateTime.of(2026, 7, 10, 10, 0, overlapSecond, 0, ZoneOffset.UTC);
        return new Conflict(passenger, "e1", "o1", "e2", "o2", start, start.plusSeconds(2), OffsetDateTime.now());
    }

    @Test
    void sortsByEarliestOverlapAndMapsToDto() {
        when(reader.conflicts()).thenReturn(Flux.just(
                conflict("carol", 30), conflict("alice", 5), conflict("bob", 10)));

        List<ConflictDto> result = service.all().collectList().block();

        assertThat(result).extracting(ConflictDto::passengerId).containsExactly("alice", "bob", "carol");
        assertThat(result.get(0).elevatorA()).isEqualTo("e1");
        assertThat(result.get(0).elevatorB()).isEqualTo("e2");
    }

    @Test
    void emptyWhenNoConflicts() {
        when(reader.conflicts()).thenReturn(Flux.empty());
        assertThat(service.all().collectList().block()).isEmpty();
    }
}
