package pl.feelcodes.elevator.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Project params used to validate incoming orders: the building height and the fleet.
 * Bound from {@code elevator.max-floor} / {@code elevator.elevators} (env {@code ELEVATOR_MAXFLOOR}
 * / {@code ELEVATOR_ELEVATORS}, set by the api ConfigMap). Defaults match the demo: 15 floors,
 * e1..e10. Read by {@code OrderValidator} so the order bounds are configuration, not constants.
 */
@ConfigurationProperties(prefix = "elevator")
public class ElevatorProperties {

    /** Highest valid floor; an order must target a floor in 0..maxFloor. */
    private int maxFloor = 15;

    /** Valid elevator names (the fleet); an order's elevator must be one of these. */
    private List<String> elevators = List.of();

    public int getMaxFloor() {
        return maxFloor;
    }

    public void setMaxFloor(int maxFloor) {
        this.maxFloor = maxFloor;
    }

    public List<String> getElevators() {
        return elevators;
    }

    public void setElevators(List<String> elevators) {
        this.elevators = elevators;
    }
}
