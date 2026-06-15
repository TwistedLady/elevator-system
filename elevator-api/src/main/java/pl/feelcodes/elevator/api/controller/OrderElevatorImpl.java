package pl.feelcodes.elevator.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.feelcodes.elevator.api.dto.OrderElevatorRequestDto;
import pl.feelcodes.elevator.api.service.OrderElevatorService;

import java.util.Objects;
import java.util.UUID;


@Slf4j
@RestController
@RequestMapping("/api/order")
public class OrderElevatorImpl implements OrderElevator {
    private final OrderElevatorService orderElevatorService;

    @Autowired
    public OrderElevatorImpl(OrderElevatorService orderElevatorService) {
        this.orderElevatorService = orderElevatorService;
    }

    @PostMapping
    public OrderElevatorRequestDto order(@RequestBody OrderElevatorRequestDto dto) throws JsonProcessingException {
        if (Objects.isNull(dto.getTag()))
            dto.setTag(UUID.randomUUID().toString());
        orderElevatorService.order(dto.getTag(), dto.getElevatorName(), dto.getFloor());
        log.info(dto.toString());
        return dto;
    }
}
