package pl.feelcodes.elevator.api.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Whether the BI (Spark analytics) layer is enabled. Seeded from {@code ELEVATOR_BI_ENABLED} and
 * hot-reloaded from the mounted ConfigMap file, so flipping the flag takes effect without a restart.
 */
@Component
public class BiState {

    private static final Logger log = LoggerFactory.getLogger(BiState.class);

    private final Path enabledFile;
    private volatile boolean enabled;

    public BiState(@Value("${elevator.bi.enabled:true}") boolean enabled,
                   @Value("${elevator.config-dir:/etc/elevator-config}") String configDir) {
        this.enabled = enabled;
        this.enabledFile = Path.of(configDir, "ELEVATOR_BI_ENABLED");
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Scheduled(fixedDelayString = "${elevator.reload-interval-ms:5000}")
    void reload() {
        try {
            if (!Files.isReadable(enabledFile)) {
                return;
            }
            boolean now = parse(Files.readString(enabledFile).trim());
            if (now != enabled) {
                enabled = now;
                log.info("BI enabled -> {}", now);
            }
        } catch (Exception ex) {
            log.warn("BI flag reload skipped (keeping current): {}", ex.getMessage());
        }
    }

    private static boolean parse(String value) {
        return value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
    }
}
