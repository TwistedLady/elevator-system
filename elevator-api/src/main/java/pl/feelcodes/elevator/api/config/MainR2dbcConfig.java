package pl.feelcodes.elevator.api.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.r2dbc.ConnectionFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Primary datasource → the operational `elevator` DB (Pekko journal + order read-models).
 * Owns the `order` repositories. The stats read-models live in a SEPARATE datasource
 * ({@link StatsR2dbcConfig}); Boot's single-datasource repository auto-config is disabled on
 * {@code ElevatorApi} so each package binds to exactly one connection factory.
 */
@Configuration
@EnableR2dbcRepositories(
        basePackages = "pl.feelcodes.elevator.api.order",
        entityOperationsRef = "mainR2dbcEntityOperations")
class MainR2dbcConfig {

    @Bean
    @Primary
    ConnectionFactory mainConnectionFactory(
            @Value("${spring.r2dbc.url}") String url,
            @Value("${spring.r2dbc.username}") String username,
            @Value("${spring.r2dbc.password}") String password) {
        return ConnectionFactoryBuilder.withUrl(url).username(username).password(password).build();
    }

    @Bean
    R2dbcEntityOperations mainR2dbcEntityOperations(
            @Qualifier("mainConnectionFactory") ConnectionFactory connectionFactory) {
        return new R2dbcEntityTemplate(connectionFactory);
    }
}
