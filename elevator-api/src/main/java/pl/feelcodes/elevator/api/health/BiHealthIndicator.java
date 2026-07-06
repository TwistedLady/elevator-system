package pl.feelcodes.elevator.api.health;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ValidationDepth;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The {@code bi} health component when BI is enabled: pings the separate postgres-stats DB. Only
 * created when {@code elevator.bi.enabled} is true (the whole stats stack is; see the controllers).
 * Not part of the readiness group, so it never flips the pod out of rotation.
 */
@Component("bi")
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class BiHealthIndicator implements ReactiveHealthIndicator {

    private final ConnectionFactory stats;

    BiHealthIndicator(@Qualifier("statsConnectionFactory") ConnectionFactory stats) {
        this.stats = stats;
    }

    @Override
    public Mono<Health> health() {
        return Mono.usingWhen(
                        stats.create(),
                        connection -> Mono.from(connection.validate(ValidationDepth.REMOTE)),
                        Connection::close)
                .map(valid -> valid ? Health.up().build() : Health.down().build())
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}
