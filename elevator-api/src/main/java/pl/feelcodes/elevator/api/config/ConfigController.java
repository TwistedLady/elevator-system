package pl.feelcodes.elevator.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
class ConfigController {

    private final ElevatorLimits limits;
    private final boolean biEnabled;

    ConfigController(ElevatorLimits limits,
                     @Value("${elevator.bi.enabled:true}") boolean biEnabled) {
        this.limits = limits;
        this.biEnabled = biEnabled;
    }

    @GetMapping
    ElevatorConfigDto config() {
        return new ElevatorConfigDto(limits.getMaxFloor(), limits.getElevators(), biEnabled);
    }
}
