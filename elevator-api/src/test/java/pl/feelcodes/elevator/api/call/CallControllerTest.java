package pl.feelcodes.elevator.api.call;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.feelcodes.elevator.api.auth.SecurityConfig;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = CallController.class)
@Import(SecurityConfig.class)
class CallControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    CallService callService;

    @MockBean
    CallStatusService callStatusService;

    @MockBean
    ElevatorLimits limits;

    @MockBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void limitsAllowTheCall() {
        when(limits.getMaxFloor()).thenReturn(15);
        when(limits.getElevators()).thenReturn(List.of("e1"));
    }

    @Test
    void call_without_a_token_is_rejected() {
        client.post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void call_with_a_valid_token_uses_the_subject_as_passenger() {
        when(callService.call(any(), any(), any(), eq("rider-3"))).thenReturn(Mono.empty());

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("rider-3")))
                .post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), eq("rider-3"));
    }

    @Test
    void passenger_in_the_body_is_ignored_the_token_subject_wins() {
        when(callService.call(any(), any(), any(), eq("rider-0"))).thenReturn(Mono.empty());

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("rider-0")))
                .post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3,\"passengerId\":\"rider-9\"}")
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), eq("rider-0"));
    }

    @Test
    void call_with_an_out_of_range_floor_is_rejected_and_never_reaches_the_service() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("rider-3")))
                .post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":999}")
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(callService);
    }

    @Test
    void call_with_a_negative_floor_is_rejected() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("rider-3")))
                .post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":-1}")
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(callService);
    }

    @Test
    void call_for_an_unknown_elevator_is_rejected() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("rider-3")))
                .post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e99\",\"floor\":3}")
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(callService);
    }

    @Test
    void call_with_an_invalid_token_is_rejected() {
        when(jwtDecoder.decode("bad-token")).thenReturn(Mono.error(new BadJwtException("bad")));

        client.post().uri("/api/call")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer bad-token")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
