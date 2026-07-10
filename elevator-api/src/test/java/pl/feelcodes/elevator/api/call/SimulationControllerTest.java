package pl.feelcodes.elevator.api.call;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = SimulationController.class)
class SimulationControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    CallService callService;

    @MockBean
    CallStatusRepository callStatusRepository;

    @MockBean
    ElevatorLimits limits;

    @BeforeEach
    void fleet() {
        when(limits.getMaxFloor()).thenReturn(15);
        when(limits.getElevators()).thenReturn(List.of("e1", "e2", "e3"));
        when(callService.call(any(), any(), any(), isNull())).thenReturn(Mono.empty());
    }

    @Test
    void simulate_generates_the_requested_number_of_calls_and_returns_their_ids() {
        client.post().uri("/api/simulate?count=4")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(4)
                .jsonPath("$.ids.length()").isEqualTo(4);

        verify(callService, times(4)).call(any(), any(), any(), isNull());
    }

    @Test
    void simulate_defaults_to_ten_thousand() {
        client.post().uri("/api/simulate")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(SimulationController.DEFAULT_COUNT);
    }

    @Test
    void simulate_clamps_a_too_large_count_to_the_max() {
        client.post().uri("/api/simulate?count=999999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(SimulationController.MAX_COUNT);
    }

    @Test
    void status_splits_the_ids_into_done_progress_and_pending() {
        CallStatusEntity done1 = entity("DONE");
        CallStatusEntity done2 = entity("DONE");
        CallStatusEntity progress = entity("PROGRESS");
        when(callStatusRepository.findAllById(any(Iterable.class)))
                .thenReturn(Flux.just(done1, done2, progress));

        client.post().uri("/api/simulate/status")
                .header("Content-Type", "application/json")
                .bodyValue("{\"ids\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(5)
                .jsonPath("$.done").isEqualTo(2)
                .jsonPath("$.progress").isEqualTo(1)
                .jsonPath("$.pending").isEqualTo(2);
    }

    @Test
    void status_of_an_empty_id_list_is_all_zero() {
        client.post().uri("/api/simulate/status")
                .header("Content-Type", "application/json")
                .bodyValue("{\"ids\":[]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(0)
                .jsonPath("$.pending").isEqualTo(0);
    }

    private static CallStatusEntity entity(String status) {
        CallStatusEntity entity = mock(CallStatusEntity.class);
        when(entity.getStatus()).thenReturn(status);
        return entity;
    }
}
