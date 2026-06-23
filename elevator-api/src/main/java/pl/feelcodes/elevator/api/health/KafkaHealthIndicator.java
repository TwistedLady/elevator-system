package pl.feelcodes.elevator.api.health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reports the broker as UP/DOWN by asking it for its cluster id. Registered under the
 * name "kafka" (Spring strips the HealthIndicator suffix) so it joins the readiness group.
 * The blocking AdminClient call is pushed off the WebFlux event loop.
 */
@Component
class KafkaHealthIndicator implements ReactiveHealthIndicator {

    private final Admin admin;

    KafkaHealthIndicator(Admin admin) {
        this.admin = admin;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() -> admin.describeCluster().clusterId().get(2, TimeUnit.SECONDS))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(3))
                .map(clusterId -> Health.up().withDetail("clusterId", clusterId).build())
                .onErrorResume(e -> Mono.just(Health.down(e).build()));
    }
}
