package pl.feelcodes.elevator.api.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
class ConfigController {

    private final ElevatorLimits limits;

    ConfigController(ElevatorLimits limits) {
        this.limits = limits;
    }

    @GetMapping
    ElevatorConfigDto config() {
        return new ElevatorConfigDto(limits.getMaxFloor(), limits.getElevators());
    }
}
