package pl.feelcodes.elevator.api.call;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.feelcodes.elevator.api.config.ElevatorLimits;
import pl.feelcodes.elevator.api.config.SecurityConfig;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

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

    private static final String BODY = "{\"elevatorName\":\"e1\",\"floor\":3}";

    @BeforeEach
    void limitsAllowTheCall() {
        when(limits.getMaxFloor()).thenReturn(15);
        when(limits.getElevators()).thenReturn(List.of("e1"));
    }

    @Test
    void anonymous_call_is_accepted_with_no_passenger() {
        when(callService.call(any(), any(), any(), isNull())).thenReturn(Mono.empty());

        client.mutateWith(csrf()).post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), isNull());
    }

    @Test
    void authenticated_call_uses_the_username_as_passenger() {
        when(callService.call(any(), any(), any(), eq("rider-3"))).thenReturn(Mono.empty());

        client.mutateWith(mockUser("rider-3")).mutateWith(csrf()).post().uri("/api/call")
                .header("Content-Type", "application/json")
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isOk();

        verify(callService).call(any(), eq("e1"), eq(3), eq("rider-3"));
    }
}
