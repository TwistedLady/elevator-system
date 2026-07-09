package pl.feelcodes.elevator.api.version;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Exposes the single build version so clients (Rust CLI + Elm web console) can check they are
 * running against a matching backend. The value is the repo-root VERSION file, copied onto the
 * classpath at build time (see the copy-resources execution in this module's pom.xml).
 */
@RestController
@RequestMapping("/api/version")
class VersionController {

    private final VersionDto version;

    VersionController(@Value("classpath:VERSION") Resource versionFile) throws IOException {
        this.version = new VersionDto(versionFile.getContentAsString(StandardCharsets.UTF_8).trim());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<VersionDto> version() {
        return Mono.just(version);
    }
}
