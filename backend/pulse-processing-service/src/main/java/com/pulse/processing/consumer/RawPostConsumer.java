package com.pulse.processing.consumer;

import com.pulse.processing.service.StreamProcessor;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka-avro")
public class RawPostConsumer {

  private static final Logger log = LoggerFactory.getLogger(RawPostConsumer.class);
  private final StreamProcessor processor;

  public RawPostConsumer(StreamProcessor processor) {
    this.processor = processor;
  }

  @KafkaListener(
    topics = "${pulse.kafka.topics.raw-posts}",
    concurrency = "${pulse.kafka.concurrency:3}"
  )
  public void onMessage(
      GenericRecord record,
      @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
      @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
    
    String text = record.get("text") == null ? "" : record.get("text").toString();
    processor.handleMessage(text);
    
    log.debug("Processed message from partition={} offset={}", partition, offset);
  }
}