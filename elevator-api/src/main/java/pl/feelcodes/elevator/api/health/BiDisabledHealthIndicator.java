package pl.feelcodes.elevator.api.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The {@code bi} health component when BI is turned off: a custom DISABLED status (kept in
 * /actuator/health, never dropped). The default status aggregator treats an unknown status as less
 * severe than UP, so the OVERALL api health stays UP while BI reads shows as unavailable.
 */
@Component("bi")
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "false")
class BiDisabledHealthIndicator implements ReactiveHealthIndicator {

    private static final Status DISABLED = new Status("DISABLED", "BI is turned off via ConfigMap");

    @Override
    public Mono<Health> health() {
        return Mono.just(Health.status(DISABLED).withDetail("reason", "ELEVATOR_BI_ENABLED=false").build());
    }
}
