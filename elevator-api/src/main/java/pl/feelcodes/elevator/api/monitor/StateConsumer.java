package pl.feelcodes.elevator.api.monitor;

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

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Background Kafka consumer that tails the elevator-state topic and keeps the
 * {@link StateStore} updated with the latest state per elevator. Plain kafka-clients
 * on a daemon thread — no extra framework, easy to reason about.
 */
@Component
public class StateConsumer {

    private static final Logger log = LoggerFactory.getLogger(StateConsumer.class);

    private final StateStore store;
    private final String bootstrapServers;
    private final String stateTopic;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public StateConsumer(StateStore store,
                         @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                         @Value("${elevator.state-topic}") String stateTopic) {
        this.store = store;
        this.bootstrapServers = bootstrapServers;
        this.stateTopic = stateTopic;
    }

    @PostConstruct
    public void start() {
        thread = new Thread(this::run, "elevator-state-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Unique group per boot + earliest => replay the whole topic on every start,
        // so the in-memory cache always rebuilds the latest state for every elevator.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "elevator-api-monitor-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(stateTopic));
        log.info("Monitoring elevator-state topic '{}' at {}", stateTopic, bootstrapServers);

        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    store.put(record.key(), record.value());
                }
            }
        } catch (WakeupException expectedOnShutdown) {
            // ignore
        } catch (Exception e) {
            log.error("state consumer stopped unexpectedly", e);
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
