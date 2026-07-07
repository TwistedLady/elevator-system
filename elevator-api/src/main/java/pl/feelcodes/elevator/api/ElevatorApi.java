package pl.feelcodes.elevator.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// One R2DBC datasource (operational `elevator`); MainR2dbcConfig scopes repositories to the `order`
// package + its connection factory, so Boot's blanket single-datasource auto-config is excluded. The
// BI stats read-model is no longer a DB — it's a Parquet file read via DuckDB (see stats package).
@SpringBootApplication(exclude = R2dbcRepositoriesAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class ElevatorApi {
    public static void main(String[] args) {
        SpringApplication.run(ElevatorApi.class, args);
    }
}
