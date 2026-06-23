package pl.feelcodes.elevator.api;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import pl.feelcodes.elevator.api.elevator.ElevatorStateConsumer;

/**
 * Plain context-load smoke test. The two beans that reach out to a real broker are
 * replaced with mocks so this runs anywhere without Kafka:
 *  - ElevatorStateConsumer: as a mock its @PostConstruct never fires, so no consumer thread starts.
 *  - elevatorCommandProducer: the mock overrides the bean, so no producer is created.
 * Anything exercising real Kafka belongs in a separate, broker-backed integration test.
 */
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
