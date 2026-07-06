package pl.feelcodes.elevator.api.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
class ConfigController {

    private final ElevatorLimits limits;
    private final BiState bi;

    ConfigController(ElevatorLimits limits, BiState bi) {
        this.limits = limits;
        this.bi = bi;
    }

    @GetMapping
    ElevatorConfigDto config() {
        return new ElevatorConfigDto(limits.getMaxFloor(), limits.getElevators(), bi.isEnabled());
    }
}
