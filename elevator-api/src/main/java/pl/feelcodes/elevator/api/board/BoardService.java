package pl.feelcodes.elevator.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.common.dto.BoardDto;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
class BoardService {
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;

    @Value("${elevator.board-topic}")
    private String boardTopic;

    BoardService(KafkaProducer<String, String> producer, ObjectMapper objectMapper) {
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    Mono<Void> board(String elevatorName, Integer floor, String passengerId) {
        return Mono.<Void>create(sink -> {
            String json;
            try {
                json = objectMapper.writeValueAsString(new BoardDto(elevatorName, floor, passengerId));
            } catch (Exception e) {
                sink.error(e);
                return;
            }
            producer.send(new ProducerRecord<>(boardTopic, elevatorName, json), (metadata, ex) -> {
                if (ex != null) {
                    sink.error(ex);
                } else {
                    sink.success();
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
