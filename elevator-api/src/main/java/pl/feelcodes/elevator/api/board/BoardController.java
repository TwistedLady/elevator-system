package pl.feelcodes.elevator.api.board;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

// The passenger tells the system they have stepped into the car. Identity is the validated Bearer
// JWT subject (the security chain guarantees a token here, so `jwt` is never null); the body says
// only which elevator and floor. This becomes a BoardDto on the elevator-board topic, which the
// app forwards to the Doorman as the Boarded it was waiting for.
@Slf4j
@RestController
@RequestMapping("/api/board")
class BoardController {

    private final BoardService boardService;

    BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @PostMapping
    public Mono<BoardRequestDto> board(@Valid @RequestBody BoardRequestDto dto,
                                       @AuthenticationPrincipal Jwt jwt) {
        String passenger = jwt.getSubject();
        log.info("[board place ] {} -> floor {} (passenger {})", dto.elevatorName(), dto.floor(), passenger);
        return boardService.board(dto.elevatorName(), dto.floor(), passenger)
                .thenReturn(dto);
    }
}
