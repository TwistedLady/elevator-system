package pl.feelcodes.elevator.api.board;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.feelcodes.elevator.api.auth.SecurityConfig;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = BoardController.class)
@Import(SecurityConfig.class)
class BoardControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    BoardService boardService;

    @MockBean
    ReactiveJwtDecoder jwtDecoder;

    @Test
    void board_without_a_token_is_rejected() {
        client.post().uri("/api/board")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void board_with_a_valid_token_uses_the_subject_as_passenger() {
        when(boardService.board(any(), any(), eq("rider-3"))).thenReturn(Mono.empty());

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("rider-3")))
                .post().uri("/api/board")
                .header("Content-Type", "application/json")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isOk();

        verify(boardService).board(eq("e1"), eq(3), eq("rider-3"));
    }

    @Test
    void board_with_an_invalid_token_is_rejected() {
        when(jwtDecoder.decode("bad-token")).thenReturn(Mono.error(new BadJwtException("bad")));

        client.post().uri("/api/board")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer bad-token")
                .bodyValue("{\"elevatorName\":\"e1\",\"floor\":3}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
