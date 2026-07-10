package pl.feelcodes.elevator.api.call;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/call")
class CallController {

    private final CallService callService;
    private final CallStatusService callStatusService;

    CallController(CallService callService, CallStatusService callStatusService) {
        this.callService = callService;
        this.callStatusService = callStatusService;
    }

    // Identity is proven, not claimed: the passenger comes from the validated Bearer JWT's subject.
    // The security chain guarantees a token is present here, so `jwt` is never null. Any
    // passengerId in the body is ignored.
    @PostMapping
    public Mono<CallRequestDto> place(@Valid @RequestBody CallRequestDto dto,
                                      @AuthenticationPrincipal Jwt jwt) {
        CallRequestDto call = dto.withIdIfAbsent();
        String passenger = jwt.getSubject();
        log.info("[call place ] {} -> floor {} (id {}, passenger {})",
                call.elevatorName(), call.floor(), call.id(), passenger);
        return callService.call(call.id(), call.elevatorName(), call.floor(), passenger)
                .thenReturn(call);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<CallStatusDto>> status(@PathVariable("id") String id) {
        return callStatusService.byId(id)
                .doOnNext(c -> log.info("[call status] {} -> {} (created {}, done {})",
                        c.id(), c.status(), c.createDateTime(), c.doneDateTime()))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .doOnNext(resp -> {
                    if (resp.getStatusCode().is4xxClientError()) {
                        log.info("[call status] {} -> NOT FOUND (404)", id);
                    }
                });
    }
}
