package pl.feelcodes.elevator.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Full API security. The passenger actions are the guarded ones: {@code POST /api/call} and
 * {@code POST /api/board} each require a valid passenger Bearer JWT (missing/invalid/expired -> 401).
 * Everything else — read endpoints, SSE streams, health, the token endpoint and JWKS — stays open.
 * Identity is enforced only here at the HTTP edge; the app/Kafka layer is untouched.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.POST, "/api/call").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/board").authenticated()
                        .anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
                .build();
    }
}
