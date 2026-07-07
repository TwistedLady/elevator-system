package pl.feelcodes.elevator.api.stats.mileage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.api.stats.ElevatorStat;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;

@Service
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class MileageService {

    private final ParquetStatsReader reader;

    MileageService(ParquetStatsReader reader) {
        this.reader = reader;
    }

    Flux<MileageDto> all() {
        return reader.all()
                .sort(Comparator.comparingLong(ElevatorStat::floorsTravelled).reversed())
                .map(MileageService::toDto);
    }

    Mono<MileageDto> byElevator(String elevatorName) {
        return reader.all()
                .filter(s -> s.elevatorName().equals(elevatorName))
                .next()
                .map(MileageService::toDto);
    }

    private static MileageDto toDto(ElevatorStat s) {
        return new MileageDto(s.elevatorName(), s.floorsTravelled(), s.updatedAt());
    }
}
