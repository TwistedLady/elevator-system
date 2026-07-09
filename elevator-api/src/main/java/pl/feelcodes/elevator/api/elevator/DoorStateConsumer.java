package pl.feelcodes.elevator.api.elevator;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.feelcodes.elevator.common.dto.DoorStateDto;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Component
public class DoorStateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DoorStateConsumer.class);

    private final DoorStateStore store;
    private final ObjectMapper objectMapper;
    private final String bootstrapServers;
    private final String doorStateTopic;
    private final String podName;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public DoorStateConsumer(DoorStateStore store,
                             ObjectMapper objectMapper,
                             @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                             @Value("${elevator.door-state-topic}") String doorStateTopic,
                             @Value("${elevator.pod-name:}") String podName) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.bootstrapServers = bootstrapServers;
        this.doorStateTopic = doorStateTopic;
        this.podName = podName;
    }

    @PostConstruct
    public void start() {
        thread = new Thread(this::run, "elevator-door-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        String suffix = (podName == null || podName.isBlank()) ? UUID.randomUUID().toString() : podName;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "elevator-api-door-monitor-" + suffix);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(doorStateTopic));
        log.info("Monitoring elevator-door-state topic '{}' at {}", doorStateTopic, bootstrapServers);

        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        store.put(record.key(), objectMapper.readValue(record.value(), DoorStateDto.class));
                    } catch (Exception parseError) {
                        log.warn("skipping unparseable door message for '{}': {}", record.key(), parseError.getMessage());
                    }
                }
            }
        } catch (WakeupException expectedOnShutdown) {
        } catch (Exception e) {
            log.error("door consumer stopped unexpectedly", e);
        } finally {
            consumer.close();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
    }
}
