package pl.feelcodes.elevator.api.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code bi} health component when BI is enabled: reports UP when the Spark BI Parquet file is
 * present and readable on the shared volume. Only created when {@code elevator.bi.enabled} is true.
 * Not part of the readiness group, so it never flips the pod out of rotation (the file is absent
 * until the first BI cycle runs).
 */
@Component("bi")
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class BiHealthIndicator implements ReactiveHealthIndicator {

    private final Path parquetDir;

    BiHealthIndicator(@Value("${elevator.stats.parquet-path:/data/elevators.parquet}") String parquetPath) {
        this.parquetDir = Path.of(parquetPath);
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(() ->
                        Files.isDirectory(parquetDir) && Files.isReadable(parquetDir)
                                ? Health.up().withDetail("parquet", parquetDir.toString()).build()
                                : Health.down().withDetail("parquet", parquetDir + " (absent)").build())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}
