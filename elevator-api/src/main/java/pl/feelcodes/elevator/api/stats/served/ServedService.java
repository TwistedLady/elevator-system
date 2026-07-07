package pl.feelcodes.elevator.api.stats.served;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.api.stats.ElevatorStat;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;

@Service
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class ServedService {

    private final ParquetStatsReader reader;

    ServedService(ParquetStatsReader reader) {
        this.reader = reader;
    }

    Flux<ServedDto> all() {
        return reader.all()
                .sort(Comparator.comparingLong(ElevatorStat::ordersServed).reversed())
                .map(ServedService::toDto);
    }

    Mono<ServedDto> byElevator(String elevatorName) {
        return reader.all()
                .filter(s -> s.elevatorName().equals(elevatorName))
                .next()
                .map(ServedService::toDto);
    }

    private static ServedDto toDto(ElevatorStat s) {
        return new ServedDto(s.elevatorName(), s.ordersServed(), s.updatedAt());
    }
}
