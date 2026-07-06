package pl.feelcodes.elevator.api.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ElevatorLimits {

    private static final Logger log = LoggerFactory.getLogger(ElevatorLimits.class);

    private final Path maxFloorFile;
    private final Path elevatorsFile;

    private volatile int maxFloor;
    private volatile List<String> elevators;

    public ElevatorLimits(ElevatorProperties props,
                          @Value("${elevator.config-dir:/etc/elevator-config}") String configDir) {
        this.maxFloorFile = Path.of(configDir, "ELEVATOR_MAXFLOOR");
        this.elevatorsFile = Path.of(configDir, "ELEVATOR_ELEVATORS");
        set(props.getMaxFloor(), props.getElevators());
    }

    public int getMaxFloor() {
        return maxFloor;
    }

    public List<String> getElevators() {
        return elevators;
    }

    private void set(int newMaxFloor, List<String> newElevators) {
        if (newMaxFloor <= 0) {
            throw new IllegalStateException(
                    "elevator.max-floor must be > 0 — is the ConfigMap present? got " + newMaxFloor);
        }
        if (newElevators == null || newElevators.isEmpty()) {
            throw new IllegalStateException("elevator.elevators must be non-empty — is the ConfigMap present?");
        }
        this.maxFloor = newMaxFloor;
        this.elevators = List.copyOf(newElevators);
    }

    @Scheduled(fixedDelayString = "${elevator.reload-interval-ms:5000}")
    void reload() {
        try {
            Integer newFloor = readMaxFloor();
            List<String> newElevators = readElevators();
            if (newFloor == null && newElevators == null) {
                return;
            }
            int floor = newFloor != null ? newFloor : maxFloor;
            List<String> fleet = newElevators != null ? newElevators : elevators;
            if (floor != maxFloor || !fleet.equals(elevators)) {
                set(floor, fleet);
                log.info("limits reloaded from ConfigMap: maxFloor={}, elevators={}", floor, fleet);
            }
        } catch (Exception ex) {
            log.warn("limits reload skipped (keeping current): {}", ex.getMessage());
        }
    }

    private Integer readMaxFloor() throws Exception {
        if (!Files.isReadable(maxFloorFile)) {
            return null;
        }
        String value = Files.readString(maxFloorFile).trim();
        return value.isEmpty() ? null : Integer.parseInt(value);
    }

    private List<String> readElevators() throws Exception {
        if (!Files.isReadable(elevatorsFile)) {
            return null;
        }
        String value = Files.readString(elevatorsFile).trim();
        return value.isEmpty() ? null : List.of(value.split("\\s*,\\s*"));
    }
}
