package pl.feelcodes.elevator.api.call;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.feelcodes.elevator.api.auth.SecurityConfig;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = SimulationController.class)
@Import(SecurityConfig.class)
class SimulationControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    CallService callService;

    @MockBean
    CallStatusRepository callStatusRepository;

    @MockBean
    ElevatorLimits limits;

    @MockBean
    ReactiveJwtDecoder jwtDecoder;

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
    void progress_rolls_up_the_runs_calls_into_a_summary() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-10T10:00:05Z");
        CallStatusEntity done = row("DONE", "order-a", t0, t1);
        CallStatusEntity progress = row("PROGRESS", "order-a", t0, null);
        CallStatusEntity fresh = row("PROGRESS", "order-b", t1, null);
        when(callStatusRepository.findByCallIdPrefix(any())).thenReturn(Flux.just(done, progress, fresh));

        client.get().uri("/api/simulate/progress?runId=abc123&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.simSize").isEqualTo(10)
                .jsonPath("$.calls").isEqualTo(3)
                .jsonPath("$.orders").isEqualTo(2)
                .jsonPath("$.doneCalls").isEqualTo(1)
                .jsonPath("$.firstCall").isEqualTo("2026-07-10T10:00:00Z")
                .jsonPath("$.lastDone").isEqualTo("2026-07-10T10:00:05Z");
    }

    @Test
    void progress_of_a_run_with_no_calls_yet_is_zeroed() {
        when(callStatusRepository.findByCallIdPrefix(any())).thenReturn(Flux.empty());

        client.get().uri("/api/simulate/progress?runId=nope&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.simSize").isEqualTo(10)
                .jsonPath("$.calls").isEqualTo(0)
                .jsonPath("$.orders").isEqualTo(0)
                .jsonPath("$.doneCalls").isEqualTo(0)
                .jsonPath("$.firstCall").doesNotExist()
                .jsonPath("$.lastDone").doesNotExist();
    }

    private static CallStatusEntity row(String status, String orderId, OffsetDateTime created, OffsetDateTime done) {
        CallStatusEntity entity = mock(CallStatusEntity.class);
        when(entity.getStatus()).thenReturn(status);
        when(entity.getOrderId()).thenReturn(orderId);
        when(entity.getCreatedAt()).thenReturn(created);
        when(entity.getDoneAt()).thenReturn(done);
        return entity;
    }
}
