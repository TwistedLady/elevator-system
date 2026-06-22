package pl.feelcodes.elevator.api;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Properties;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end monitor read path against a REAL Kafka broker (Testcontainers).
 *
 * Where {@link ElevatorApiTests} mocks Kafka to prove the context wires up, this boots the
 * real {@link pl.feelcodes.elevator.api.elevator.ElevatorStateConsumer}:
 *   publish a state event -> consumer stores it -> REST endpoint serves it.
 *
 * Opt-in: named *IT so it runs under failsafe (`mvn verify`), never under surefire (`mvn test`).
 * Requires a running Docker daemon.
 */
@Testcontainers
@SpringBootTest(classes = ElevatorApi.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ElevatorStateFlowIT {

    // Same broker version as the demo/k8s brokers (kafka.version in the root pom).
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

    @Test
    void publishedStateBecomesVisibleViaRestEndpoint() {
        String elevator = "alpha";
        String stateJson =
                "{\"elevatorName\":\"alpha\",\"floor\":3,\"direction\":\"UP\",\"motion\":\"MOVING\"}";

        try (KafkaProducer<String, String> producer = newProducer()) {
            producer.send(new ProducerRecord<>("elevator-state", elevator, stateJson));
            producer.flush();
        }

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // StateConsumer reads from earliest on a fresh group, so the event lands shortly after boot.
        await().atMost(30, SECONDS).untilAsserted(() ->
                client.get().uri("/api/elevator/{name}", elevator)
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody().json(stateJson));
    }

    private static KafkaProducer<String, String> newProducer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }
}
