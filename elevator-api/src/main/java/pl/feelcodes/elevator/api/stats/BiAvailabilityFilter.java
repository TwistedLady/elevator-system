package pl.feelcodes.elevator.api.stats;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pl.feelcodes.elevator.api.config.BiState;
import reactor.core.publisher.Mono;

/**
 * When BI is disabled, short-circuit the analytics endpoints (/api/mileage, /api/served) with a
 * clear 503 instead of letting them hit the (absent) postgres-stats DB. The API stays up; only the
 * BI reads are unavailable.
 */
@Component
class BiAvailabilityFilter implements WebFilter {

    private static final String BODY =
            "{\"error\":\"BI is disabled\",\"detail\":\"Analytics (mileage/served) are turned off. "
            + "Set ELEVATOR_BI_ENABLED=true in the elevator-config ConfigMap to enable them.\"}";

    private final BiState bi;

    BiAvailabilityFilter(BiState bi) {
        this.bi = bi;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!bi.isEnabled() && (path.startsWith("/api/mileage") || path.startsWith("/api/served"))) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            DataBuffer buffer = response.bufferFactory().wrap(BODY.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        }
        return chain.filter(exchange);
    }
}
