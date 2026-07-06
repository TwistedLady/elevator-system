package pl.feelcodes.elevator.api.health;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ValidationDepth;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;
import pl.feelcodes.elevator.api.config.BiState;
import reactor.core.publisher.Mono;

/**
 * Health of the BI layer, surfaced as the {@code bi} component (kept in /actuator/health, never
 * dropped). When BI is off it reports a custom {@code DISABLED} status — which the default status
 * aggregator treats as less severe than UP, so the OVERALL api health stays UP. When BI is on it
 * pings the separate postgres-stats DB. Not part of the readiness group, so it never flips the pod
 * out of rotation.
 */
@Component("bi")
class BiHealthIndicator implements ReactiveHealthIndicator {

    static final Status DISABLED = new Status("DISABLED", "BI is turned off via ConfigMap");

    private final BiState bi;
    private final ConnectionFactory stats;

    BiHealthIndicator(BiState bi, @Qualifier("statsConnectionFactory") ConnectionFactory stats) {
        this.bi = bi;
        this.stats = stats;
    }

    @Override
    public Mono<Health> health() {
        if (!bi.isEnabled()) {
            return Mono.just(Health.status(DISABLED).withDetail("reason", "ELEVATOR_BI_ENABLED=false").build());
        }
        return Mono.usingWhen(
                        stats.create(),
                        connection -> Mono.from(connection.validate(ValidationDepth.REMOTE)),
                        Connection::close)
                .map(valid -> valid ? Health.up().build() : Health.down().build())
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}
