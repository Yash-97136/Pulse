package com.pulse.api.controller;

import com.pulse.api.model.AnomaliesResponse;
import com.pulse.api.service.AnomalyQueryService;
import com.pulse.api.service.AnomalyStreamService;
import java.util.Optional;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AnomaliesController {
  private final AnomalyQueryService anomalies;
  private final AnomalyStreamService stream;

  public AnomaliesController(AnomalyQueryService anomalies, Optional<AnomalyStreamService> stream) {
    this.anomalies = anomalies;
    this.stream = stream.orElse(null);
  }

  @GetMapping("/api/anomalies")
  public AnomaliesResponse getAnomalies(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "minZ", required = false) Double minZ,
      @RequestParam(name = "since", required = false) String sinceStr
  ) {
    Instant since = null;
    if (sinceStr != null && !sinceStr.isBlank()) {
      try { since = Instant.parse(sinceStr); } catch (Exception ignored) {}
    }
    return anomalies.latest(page, limit, keyword, minZ, since);
  }

  @GetMapping(path = "/api/anomalies/stream", produces = "text/event-stream")
  public SseEmitter streamAnomalies(@RequestParam(name = "timeoutMs", defaultValue = "300000") long timeoutMs) {
    if (stream == null) {
      // In redis-pipeline mode, Kafka-based SSE is disabled. Return a short-lived emitter
      // that sends a note so clients don't error on connect.
      SseEmitter emitter = new SseEmitter(timeoutMs);
      try {
        emitter.send(SseEmitter.event().name("hello").data("connected (redis mode, no SSE)"));
        emitter.complete();
      } catch (Exception e) {
        try { emitter.complete(); } catch (Exception ignored) {}
      }
      return emitter;
    }
    return stream.registerClient(timeoutMs);
  }
}
