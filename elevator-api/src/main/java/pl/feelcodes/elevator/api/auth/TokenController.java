package pl.feelcodes.elevator.api.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/**
 * Dev identity endpoints: mint a passenger token ({@code POST /api/token}) and publish the public
 * signing key ({@code GET /oauth2/jwks}). The mint is gated by a shared client secret so it is not
 * world-open; it stands in for a real passenger login until one is built.
 */
@Slf4j
@RestController
class TokenController {

    private final TokenService tokenService;
    private final AuthProperties props;
    private final RSAKey rsaKey;

    TokenController(TokenService tokenService, AuthProperties props, RSAKey rsaKey) {
        this.tokenService = tokenService;
        this.props = props;
        this.rsaKey = rsaKey;
    }

    record TokenRequest(String subject) {}

    record TokenResponse(String token, long expiresInSeconds) {}

    @PostMapping(value = "/api/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TokenResponse> issue(@RequestHeader(value = "X-Client-Secret", required = false) String secret,
                                     @RequestBody TokenRequest request) {
        if (!secretMatches(secret)) {
            log.info("[token issue ] rejected: bad or missing client secret");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid client secret");
        }
        if (request == null || request.subject() == null || request.subject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject is required");
        }
        return Mono.fromCallable(() -> {
            String token = tokenService.issue(request.subject());
            log.info("[token issue ] subject {} (ttl {}s)", request.subject(), props.getTokenTtlSeconds());
            return new TokenResponse(token, props.getTokenTtlSeconds());
        });
    }

    @GetMapping(value = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    private boolean secretMatches(String provided) {
        String expected = props.getClientSecret();
        if (expected == null || expected.isEmpty() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
