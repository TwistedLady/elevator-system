package pl.feelcodes.elevator.api.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.r2dbc.ConnectionFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Dedicated datasource → the SEPARATE `postgres-stats` DB (elevator_stats) holding the Spark BI
 * read-models. Owns the `stats.*` repositories (mileage + served), isolated from the operational
 * datasource ({@link MainR2dbcConfig}).
 */
@Configuration
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableR2dbcRepositories(
        basePackages = "pl.feelcodes.elevator.api.stats",
        entityOperationsRef = "statsR2dbcEntityOperations")
class StatsR2dbcConfig {

    @Bean
    ConnectionFactory statsConnectionFactory(
            @Value("${elevator.stats.r2dbc.url}") String url,
            @Value("${elevator.stats.r2dbc.username}") String username,
            @Value("${elevator.stats.r2dbc.password}") String password) {
        return ConnectionFactoryBuilder.withUrl(url).username(username).password(password).build();
    }

    @Bean
    R2dbcEntityOperations statsR2dbcEntityOperations(
            @Qualifier("statsConnectionFactory") ConnectionFactory connectionFactory) {
        return new R2dbcEntityTemplate(connectionFactory);
    }
}
