package pl.feelcodes.elevator.api.served;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ServedServiceTest {

    private final ServedRepository repository = Mockito.mock(ServedRepository.class);
    private final ServedService service = new ServedService(repository);

    private static ServedEntity entity(String name, long served) {
        ServedEntity e = Mockito.mock(ServedEntity.class);
        when(e.getElevatorName()).thenReturn(name);
        when(e.getOrdersServed()).thenReturn(served);
        when(e.getUpdatedAt()).thenReturn(OffsetDateTime.now());
        return e;
    }

    @Test
    void allMapsEntitiesToDtosPreservingRepositoryOrder() {
        ServedEntity e4 = entity("e4", 15);
        ServedEntity e10 = entity("e10", 13);
        when(repository.findAllByOrderByOrdersServedDesc()).thenReturn(Flux.just(e4, e10));

        List<ServedDto> result = service.all().collectList().block();

        assertThat(result).extracting(ServedDto::elevatorName).containsExactly("e4", "e10");
        assertThat(result).extracting(ServedDto::ordersServed).containsExactly(15L, 13L);
    }

    @Test
    void byElevatorMapsToDto() {
        ServedEntity e9 = entity("e9", 11);
        when(repository.findById("e9")).thenReturn(Mono.just(e9));

        ServedDto result = service.byElevator("e9").block();

        assertThat(result).isNotNull();
        assertThat(result.elevatorName()).isEqualTo("e9");
        assertThat(result.ordersServed()).isEqualTo(11L);
    }

    @Test
    void byElevatorEmptyWhenMissing() {
        when(repository.findById("nope")).thenReturn(Mono.empty());

        assertThat(service.byElevator("nope").block()).isNull();
    }
}
