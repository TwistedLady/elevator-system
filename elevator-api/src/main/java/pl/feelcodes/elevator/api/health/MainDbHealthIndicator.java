package pl.feelcodes.elevator.api.health;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ValidationDepth;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Health of the operational (main) database as the {@code db} component. Boot's built-in r2dbc
 * health is disabled (it would also flag the optional postgres-stats DB when BI is off); this pings
 * only the main datasource so it stays monitored.
 */
@Component("db")
class MainDbHealthIndicator implements ReactiveHealthIndicator {

    private final ConnectionFactory main;

    MainDbHealthIndicator(@Qualifier("mainConnectionFactory") ConnectionFactory main) {
        this.main = main;
    }

    @Override
    public Mono<Health> health() {
        return Mono.usingWhen(
                        main.create(),
                        connection -> Mono.from(connection.validate(ValidationDepth.REMOTE)),
                        Connection::close)
                .map(valid -> valid ? Health.up().build() : Health.down().build())
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}
