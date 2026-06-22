package pl.feelcodes.elevator.api.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto;

@Service
class OrderService {
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;

    @Value("${elevator.command-topic}")
    private String commandTopic;

    OrderService(KafkaProducer<String, String> producer, ObjectMapper objectMapper) {
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    void order(String tag, String elevatorName, Integer floor) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(new ElevatorOrderDto(tag, elevatorName, floor));
        producer.send(new ProducerRecord<>(commandTopic, elevatorName, json));
    }
}
