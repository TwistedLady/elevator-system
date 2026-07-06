package pl.feelcodes.elevator.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    public OpenAPI elevatorOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Elevator API")
                .description("HTTP edge for the event-sourced elevator system: order intake and elevator state.")
                .version("v1"));
    }
}
