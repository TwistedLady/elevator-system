package pl.feelcodes.elevator.api.stats.served;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.feelcodes.elevator.api.stats.ElevatorStat;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ServedServiceTest {

    private final ParquetStatsReader reader = Mockito.mock(ParquetStatsReader.class);
    private final ServedService service = new ServedService(reader);

    private static ElevatorStat stat(String name, long served) {
        return new ElevatorStat(name, 0L, served, OffsetDateTime.now());
    }

    @Test
    void allSortsByOrdersServedDescRegardlessOfReaderOrder() {
        when(reader.all()).thenReturn(Flux.just(stat("e10", 13), stat("e4", 15), stat("e9", 11)));

        List<ServedDto> result = service.all().collectList().block();

        assertThat(result).extracting(ServedDto::elevatorName).containsExactly("e4", "e10", "e9");
        assertThat(result).extracting(ServedDto::ordersServed).containsExactly(15L, 13L, 11L);
    }

    @Test
    void byElevatorMapsToDto() {
        when(reader.all()).thenReturn(Flux.just(stat("e9", 11)));

        ServedDto result = service.byElevator("e9").block();

        assertThat(result).isNotNull();
        assertThat(result.elevatorName()).isEqualTo("e9");
        assertThat(result.ordersServed()).isEqualTo(11L);
    }

    @Test
    void byElevatorEmptyWhenMissing() {
        when(reader.all()).thenReturn(Flux.just(stat("e9", 11)));

        assertThat(service.byElevator("nope").block()).isNull();
    }

    @Test
    void allIsEmptyOnColdStartWhenNoParquetHasBeenWrittenYet() {
        when(reader.all()).thenReturn(Flux.empty());

        assertThat(service.all().collectList().block()).isEmpty();
        assertThat(service.byElevator("e9").block()).isNull();
    }

    @Test
    void dtoCarriesTheSnapshotTime() {
        OffsetDateTime snapshot = OffsetDateTime.parse("2026-07-10T12:00:00Z");
        when(reader.all()).thenReturn(Flux.just(new ElevatorStat("e9", 0L, 11, snapshot)));

        assertThat(service.byElevator("e9").block().updatedAt()).isEqualTo(snapshot);
    }
}
