package pl.feelcodes.elevator.api.stats.served;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
class ServedService {

    private final ServedRepository repository;

    ServedService(ServedRepository repository) {
        this.repository = repository;
    }

    Flux<ServedDto> all() {
        return repository.findAllByOrderByOrdersServedDesc().map(ServedService::toDto);
    }

    Mono<ServedDto> byElevator(String elevatorName) {
        return repository.findById(elevatorName).map(ServedService::toDto);
    }

    private static ServedDto toDto(ServedEntity e) {
        return new ServedDto(e.getElevatorName(), e.getOrdersServed(), e.getUpdatedAt());
    }
}
