package pl.feelcodes.elevator.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "elevator")
public class ElevatorProperties {

    private int maxFloor = 15;

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
