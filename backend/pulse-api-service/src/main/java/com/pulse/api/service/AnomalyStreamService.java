package com.pulse.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.api.model.AnomalyEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Profile("!redis-pipeline")
public class AnomalyStreamService {
  private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();
  private final ObjectMapper mapper;

  public AnomalyStreamService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public SseEmitter registerClient(long timeoutMs) {
    SseEmitter emitter = new SseEmitter(timeoutMs);
    clients.add(emitter);
    emitter.onCompletion(() -> clients.remove(emitter));
    emitter.onTimeout(() -> clients.remove(emitter));
    try {
      emitter.send(SseEmitter.event().name("hello").data("connected"));
    } catch (IOException ignored) {}
    return emitter;
  }

  @KafkaListener(topics = "${pulse.kafka.anomalies-topic:detected_anomalies}")
  public void onAnomaly(ConsumerRecord<String, String> record) {
    AnomalyEvent event = parseEvent(record.value());
    if (event == null) return;
    for (SseEmitter client : clients) {
      try {
        client.send(SseEmitter.event().name("anomaly").data(event));
      } catch (IOException e) {
        clients.remove(client);
      }
    }
  }

  private AnomalyEvent parseEvent(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = mapper.readValue(value, Map.class);
      String id = m.get("id") != null ? String.valueOf(m.get("id")) : "";
      String keyword = m.get("keyword") != null ? String.valueOf(m.get("keyword")) : "";
      double z = toDouble(m.get("zScore"));
      long baseline = toLong(m.get("baselineVolume"));
      long current = toLong(m.get("currentVolume"));
      Instant created = parseInstant(m.get("createdAt"));
      return new AnomalyEvent(id, keyword, z, baseline, current, created != null ? created : Instant.now());
    } catch (Exception e) {
      return null;
    }
  }

  private static long toLong(Object o) {
    if (o instanceof Number n) return n.longValue();
    try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
  }
  private static double toDouble(Object o) {
    if (o instanceof Number n) return n.doubleValue();
    try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
  }
  private static Instant parseInstant(Object o) {
    if (o == null) return null;
    try { return Instant.parse(String.valueOf(o)); } catch (Exception e) { return null; }
  }
}
