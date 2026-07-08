package pl.feelcodes.elevator.api.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.springframework.security.config.Customizer.withDefaults;

// Optional HTTP Basic auth. Every endpoint is permitAll, so anonymous calls still work; when a
// request carries valid credentials the authenticated username is available as the passenger.
// Bad credentials are rejected (401). Users live in passengers.properties — a lab stand-in for a
// real directory; passwords are stored plainly with the {noop} encoder.
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .httpBasic(withDefaults())
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }

    @Bean
    MapReactiveUserDetailsService passengers() throws IOException {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource("passengers.properties").getInputStream()) {
            props.load(in);
        }
        List<UserDetails> users = props.stringPropertyNames().stream()
                .map(name -> (UserDetails) User.withUsername(name)
                        .password("{noop}" + props.getProperty(name))
                        .roles("PASSENGER")
                        .build())
                .toList();
        return new MapReactiveUserDetailsService(users);
    }
}
