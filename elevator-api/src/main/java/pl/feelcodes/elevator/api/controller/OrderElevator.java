package pl.feelcodes.elevator.api.controller;

import pl.feelcodes.elevator.api.dto.OrderElevatorRequestDto;


interface OrderElevator {
    OrderElevatorRequestDto order(OrderElevatorRequestDto orderElevatorRequestDto) throws Exception;
}
