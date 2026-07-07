package pl.feelcodes.elevator.api.stats.mileage;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.feelcodes.elevator.api.stats.ElevatorStat;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class MileageServiceTest {

    private final ParquetStatsReader reader = Mockito.mock(ParquetStatsReader.class);
    private final MileageService service = new MileageService(reader);

    private static ElevatorStat stat(String name, long floors, long served) {
        return new ElevatorStat(name, floors, served, OffsetDateTime.now());
    }

    @Test
    void allSortsByFloorsTravelledDescRegardlessOfReaderOrder() {
        when(reader.all()).thenReturn(Flux.just(stat("e1", 59, 0), stat("e4", 65, 0), stat("e2", 12, 0)));

        List<MileageDto> result = service.all().collectList().block();

        assertThat(result).extracting(MileageDto::elevatorName).containsExactly("e4", "e1", "e2");
        assertThat(result).extracting(MileageDto::floorsTravelled).containsExactly(65L, 59L, 12L);
    }

    @Test
    void byElevatorMapsToDto() {
        when(reader.all()).thenReturn(Flux.just(stat("e1", 59, 3)));

        MileageDto result = service.byElevator("e1").block();

        assertThat(result).isNotNull();
        assertThat(result.elevatorName()).isEqualTo("e1");
        assertThat(result.floorsTravelled()).isEqualTo(59L);
    }

    @Test
    void byElevatorEmptyWhenMissing() {
        when(reader.all()).thenReturn(Flux.just(stat("e1", 59, 3)));

        assertThat(service.byElevator("nope").block()).isNull();
    }
}
