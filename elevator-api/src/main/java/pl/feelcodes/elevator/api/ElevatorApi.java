package pl.feelcodes.elevator.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Two R2DBC datasources (operational `elevator` + analytics `postgres-stats`), so Boot's
// single-datasource repository auto-config is excluded — MainR2dbcConfig / StatsR2dbcConfig each
// enable repositories for their own package + connection factory.
@SpringBootApplication(exclude = R2dbcRepositoriesAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ElevatorApi {
    public static void main(String[] args) {
        SpringApplication.run(ElevatorApi.class, args);
    }
}
