package pl.feelcodes.elevator.api.mileage;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class MileageServiceTest {

    private final MileageRepository repository = Mockito.mock(MileageRepository.class);
    private final MileageService service = new MileageService(repository);

    // Build the entity fully before it is used inside any repository stubbing (Mockito
    // forbids nested when(...) calls).
    private static MileageEntity entity(String name, long floors) {
        MileageEntity e = Mockito.mock(MileageEntity.class);
        when(e.getElevatorName()).thenReturn(name);
        when(e.getFloorsTravelled()).thenReturn(floors);
        when(e.getUpdatedAt()).thenReturn(OffsetDateTime.now());
        return e;
    }

    @Test
    void allMapsEntitiesToDtosPreservingRepositoryOrder() {
        MileageEntity e4 = entity("e4", 65);
        MileageEntity e1 = entity("e1", 59);
        when(repository.findAllByOrderByFloorsTravelledDesc()).thenReturn(Flux.just(e4, e1));

        List<MileageDto> result = service.all().collectList().block();

        assertThat(result).extracting(MileageDto::elevatorName).containsExactly("e4", "e1");
        assertThat(result).extracting(MileageDto::floorsTravelled).containsExactly(65L, 59L);
    }

    @Test
    void byElevatorMapsToDto() {
        MileageEntity e1 = entity("e1", 59);
        when(repository.findById("e1")).thenReturn(Mono.just(e1));

        MileageDto result = service.byElevator("e1").block();

        assertThat(result).isNotNull();
        assertThat(result.elevatorName()).isEqualTo("e1");
        assertThat(result.floorsTravelled()).isEqualTo(59L);
    }

    @Test
    void byElevatorEmptyWhenMissing() {
        when(repository.findById("nope")).thenReturn(Mono.empty());

        assertThat(service.byElevator("nope").block()).isNull();
    }
}
