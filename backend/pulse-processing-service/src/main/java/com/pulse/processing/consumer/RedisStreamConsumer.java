package com.pulse.processing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.processing.service.StreamProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;

@Component
@Profile("redis-pipeline")
public class RedisStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final StreamProcessor processor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry;
    private final Counter messagesConsumed;
    private final Counter messagesFailed;

    @Value("${pulse.redis.stream.name:raw_posts}")
    private String streamName;

    @Value("${pulse.redis.stream.group:pulse-processing}")
    private String group;

    @Value("${pulse.redis.stream.consumer:processor-1}")
    private String consumerName;

    public RedisStreamConsumer(RedisConnectionFactory connectionFactory,
                               StringRedisTemplate redisTemplate,
                               StreamProcessor processor,
                               MeterRegistry meterRegistry) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.processor = processor;
        this.meterRegistry = meterRegistry;
        this.messagesConsumed = meterRegistry.counter("pulse_stream_messages_consumed_total");
        this.messagesFailed = meterRegistry.counter("pulse_stream_messages_failed_total");
    }

    @PostConstruct
    public void start() {
        // Ensure stream and group exist
        try {
            // Ensure stream key exists (XADD bootstrap if missing)
            try {
                var bootstrap = new HashMap<String, String>();
                bootstrap.put("meta", "init");
                redisTemplate.opsForStream().add(StreamRecords.mapBacked(bootstrap).withStreamKey(streamName));
            } catch (Exception ignored) {}

            // Create consumer group if missing (equivalent to: XGROUP CREATE stream group 0-0 MKSTREAM)
            int groupCount = 0;
            boolean groupExists = false;
            try {
                var groupInfos = redisTemplate.opsForStream().groups(streamName);
                groupExists = groupInfos != null && groupInfos.stream().anyMatch(g -> group.equals(g.groupName()));
                if (!groupExists) {
                    redisTemplate.opsForStream().createGroup(streamName, ReadOffset.from("0-0"), group);
                    log.info("Created Redis Stream group '{}' on '{}' starting at 0-0", group, streamName);
                } else {
                    log.info("Redis Stream group '{}' already exists on '{}'", group, streamName);
                }
                groupCount = groupInfos != null ? groupInfos.size() : 0;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.contains("BUSYGROUP")) {
                    log.info("Redis Stream group '{}' already exists on '{}' (BUSYGROUP)", group, streamName);
                } else {
                    log.warn("Could not ensure Redis Stream group '{}' on '{}': {}", group, streamName, msg);
                }
            }

            try {
                Long len = redisTemplate.opsForStream().size(streamName);
                log.info("Redis stream ready: name='{}' len={} group='{}' exists={} consumer='{}' groupsPresent={}" 
                    , streamName, len, group, groupExists, consumerName, groupCount);
            } catch (Exception ignored) {}

            StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                    .builder()
                    .pollTimeout(Duration.ofSeconds(1))
                    .build();

            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

            Consumer consumer = Consumer.from(group, consumerName);

            container.receiveAutoAck(consumer, StreamOffset.create(streamName, ReadOffset.lastConsumed()), message -> {
                try {
                    String payload = message.getValue().getOrDefault("payload", null);
                    if (payload == null) return;
                    JsonNode node = objectMapper.readTree(payload);
                    JsonNode textNode = node.get("text");
                    if (textNode == null || textNode.isNull()) return;
                    String text = textNode.asText("");
                    if (!text.isEmpty()) {
                        processor.handleMessage(text);
                        messagesConsumed.increment();
                        if (log.isDebugEnabled()) {
                            log.debug("Consumed stream record id={} textLen={}", message.getId(), text.length());
                        }
                    }
                } catch (Exception ex) {
                    messagesFailed.increment();
                    log.debug("Failed to process stream record {}: {}", message.getId(), ex.getMessage());
                }
            });

            container.start();
            log.info("Redis stream consumer started: stream='{}', group='{}', consumer='{}'", streamName, group, consumerName);
        } catch (Exception e) {
            log.error("Failed to start Redis stream consumer: {}", e.getMessage());
        }
    }
}
