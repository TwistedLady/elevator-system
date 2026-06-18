package pl.feelcodes.elevator.api.health;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** A shared Kafka AdminClient, reused by the health check below. */
@Configuration
class KafkaAdminConfig {

    @Bean(destroyMethod = "close")
    Admin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "2000"));
    }
}

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
