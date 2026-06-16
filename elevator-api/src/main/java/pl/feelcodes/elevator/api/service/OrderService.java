package pl.feelcodes.elevator.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;


@Service
public class OrderService {
    private final KafkaProducer<String, String> producer;
    @Value("${elevator.command-topic}")
    private String commandTopic;

    public OrderService(KafkaProducer<String, String> producer) {
        this.producer = producer;
    }

    public void order(String tag, String elevatorName, Integer floor) throws JsonProcessingException {
        Map<String, Object> payload = Map.of(
                "tag", tag,
                "elevatorName", elevatorName,
                "floor", floor
        );

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(payload);
        producer.send(
                new ProducerRecord<>(
                        commandTopic,
                        elevatorName,
                        json));
    }
}
