package pl.feelcodes.elevator.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    /**
     * Publish the order command to Kafka. The returned {@link Mono} completes only when the broker
     * has acknowledged the record, and errors if the send fails — so the caller sees a real result
     * instead of fire-and-forget. The send runs on a {@code boundedElastic} thread because the
     * first send to a topic can block while producer metadata is fetched; we keep that off the
     * WebFlux event loop.
     */
    Mono<Void> order(String tag, String elevatorName, Integer floor) {
        return Mono.<Void>create(sink -> {
            String json;
            try {
                json = objectMapper.writeValueAsString(new ElevatorOrderDto(tag, elevatorName, floor));
            } catch (Exception e) {
                sink.error(e);
                return;
            }
            producer.send(new ProducerRecord<>(commandTopic, elevatorName, json), (metadata, ex) -> {
                if (ex != null) {
                    sink.error(ex);
                } else {
                    sink.success();
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
