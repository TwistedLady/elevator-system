package pl.feelcodes.elevator.api.call;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = CallController.class)
class CallControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    CallService callService;

    @MockBean
    CallStatusService callStatusService;

    @MockBean
    ElevatorLimits limits;

    @BeforeEach
    void limitsAllowTheCall() {
        when(limits.getMaxFloor()).thenReturn(15);
        when(limits.getElevators()).thenReturn(List.of("e1"));
    }

    @Test
    void call_without_passenger_is_accepted_as_anonymous() {
        when(callService.call(any(), any(), any(), isNull())).thenReturn(Mono.empty());

        client.post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), isNull());
    }

    @Test
    void call_with_a_passenger_in_the_body_carries_it_through() {
        when(callService.call(any(), any(), any(), eq("rider-3"))).thenReturn(Mono.empty());

        client.post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3,\"passengerId\":\"rider-3\"}")
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), eq("rider-3"));
    }

    @Test
    void blank_passenger_is_treated_as_anonymous() {
        when(callService.call(any(), any(), any(), isNull())).thenReturn(Mono.empty());

        client.post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3,\"passengerId\":\"  \"}")
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), isNull());
    }
}
