package pl.feelcodes.elevator.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;

public interface OrderElevatorService {
    void order(String tag, String elevatorName, Integer floor) throws JsonProcessingException;
}
