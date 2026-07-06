package pl.feelcodes.elevator.api.mileage;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
class MileageService {

    private final MileageRepository repository;

    MileageService(MileageRepository repository) {
        this.repository = repository;
    }

    Flux<MileageDto> all() {
        return repository.findAllByOrderByFloorsTravelledDesc().map(MileageService::toDto);
    }

    Mono<MileageDto> byElevator(String elevatorName) {
        return repository.findById(elevatorName).map(MileageService::toDto);
    }

    private static MileageDto toDto(MileageEntity e) {
        return new MileageDto(e.getElevatorName(), e.getFloorsTravelled(), e.getUpdatedAt());
    }
}
