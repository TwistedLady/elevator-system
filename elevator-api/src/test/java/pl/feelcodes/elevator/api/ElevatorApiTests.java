package pl.feelcodes.elevator.api;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import pl.feelcodes.elevator.api.elevator.ElevatorStateConsumer;

@SpringBootTest
class ElevatorApiTests {

    @MockitoBean
    ElevatorStateConsumer elevatorStateConsumer;

    @MockitoBean
    KafkaProducer<String, String> elevatorCommandProducer;

    @Test
    void contextLoads() {
    }

}
