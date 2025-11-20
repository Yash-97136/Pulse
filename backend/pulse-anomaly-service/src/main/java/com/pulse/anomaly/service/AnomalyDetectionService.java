package com.pulse.anomaly.service;

import com.pulse.anomaly.model.AnomalyEvent;
import com.pulse.anomaly.repo.AnomalyEventRepository;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AnomalyDetectionService {

  private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

  private final StringRedisTemplate redis;
  private final AnomalyEventRepository anomalyRepo;
  private final KafkaTemplate<String, GenericRecord> kafka;
  private final String zsetKey;                 
  private final String anomalyTopic;
  private final double zThreshold;
  private final long cooldownSeconds;
  private final double minZStep;
  private final long lastZTtlSeconds; 
  private final double baselineVolumeMin;

  private final int historyWindow;
  private final Schema anomalySchema;
  private final String lastCountsHash;
  private final String lockKey;
  private final long lockTtlMs;
  private final MeterRegistry metrics;
  private final Counter anomaliesEmitted;
  private final Counter anomaliesSuppressedLowBaseline;
  private final Counter schedulerRuns;
  private final Timer schedulerDuration;
  private final long historyTtlSeconds;
  private final int minSamples;

  // Time-based candidate scanning params
  private final String activityZsetKey;       
  private final long activityHorizonSeconds;  
  private final long activityRetentionSeconds;

  public AnomalyDetectionService(StringRedisTemplate redis,
                                 AnomalyEventRepository anomalyRepo,
                                 KafkaTemplate<String, GenericRecord> kafka,
                                 @Value("${pulse.trends.zset-key}") String zsetKey,
                                 @Value("${pulse.anomalies.topic}") String anomalyTopic,
                                 @Value("${pulse.anomalies.z-threshold}") double zThreshold,
                                 @Value("${pulse.anomalies.history-window}") int historyWindow,
                                 @Value("${pulse.trends.last-counts-hash}") String lastCountsHash,
                                 @Value("${pulse.scheduler.lock-key}") String lockKey,
                                 @Value("${pulse.scheduler.lock-ttl-ms}") long lockTtlMs,
                                 @Value("${pulse.anomalies.cooldown-seconds:60}") long cooldownSeconds,
                                 @Value("${pulse.anomalies.min-z-step:0.5}") double minZStep,
                                 @Value("${pulse.anomalies.last-z-ttl-seconds:86400}") long lastZTtlSeconds,
                                 @Value("${pulse.anomalies.baseline-volume-min:20}") double baselineVolumeMin,
                                 @Value("${pulse.anomalies.history-ttl-seconds:172800}") long historyTtlSeconds,
                                 @Value("${pulse.anomalies.min-samples:10}") int minSamples,
                                 @Value("${pulse.trends.activity-zset-key:trends:lastSeen}") String activityZsetKey,
                                 @Value("${pulse.anomalies.activity-horizon-seconds:3600}") long activityHorizonSeconds,
                                 @Value("${pulse.anomalies.activity-retention-seconds:86400}") long activityRetentionSeconds,
                                 MeterRegistry metrics) {
    this.redis = redis;
    this.anomalyRepo = anomalyRepo;
    this.kafka = kafka;
    this.zsetKey = zsetKey;
    this.anomalyTopic = anomalyTopic;
    this.zThreshold = zThreshold;
    this.historyWindow = historyWindow;
    this.anomalySchema = loadSchema("/avro/detected_anomaly.avsc");
    this.lastCountsHash = lastCountsHash;
    this.lockKey = lockKey;
    this.lockTtlMs = lockTtlMs;
    this.cooldownSeconds = cooldownSeconds;
    this.minZStep = minZStep;
    this.lastZTtlSeconds = lastZTtlSeconds;
    this.baselineVolumeMin = baselineVolumeMin;
    this.metrics = metrics;
    this.anomaliesEmitted = metrics.counter("pulse_anomalies_emitted_total");
    this.anomaliesSuppressedLowBaseline = metrics.counter("pulse_anomalies_suppressed_total", "reason", "low_baseline");
    this.schedulerRuns = metrics.counter("pulse_scheduler_runs_total");
    this.schedulerDuration = metrics.timer("pulse_scheduler_run_duration_seconds");
    this.historyTtlSeconds = historyTtlSeconds;
    this.minSamples = minSamples;
    this.activityZsetKey = activityZsetKey;
    this.activityHorizonSeconds = activityHorizonSeconds;
    this.activityRetentionSeconds = activityRetentionSeconds;
  }

  private Schema loadSchema(String path) {
    try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(path))) {
      return new Schema.Parser().parse(in);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load Avro schema: " + path, e);
    }
  }

  // --- TASK 1: THE RECORDER (Fast, Frequent) ---
  // Records history every 5 seconds regardless of anomaly detection speed.
  @Scheduled(fixedDelayString = "${pulse.scheduler.interval-ms}")
  public void recordHistory() {
    Instant start = Instant.now();
    try {
      log.info("[recordHistory] Scheduler started at {}", start);
      long nowSec = start.getEpochSecond();

      Set<String> recent = Optional.ofNullable(
          redis.opsForZSet().rangeByScore(activityZsetKey,
              nowSec - activityHorizonSeconds,
              Double.POSITIVE_INFINITY)
      ).orElseGet(Set::of);

      if (recent.isEmpty()) {
        log.info("[recordHistory] No active keywords found in the last {}s.", activityHorizonSeconds);
        return;
      }

      Map<String, Long> currentCounts = new HashMap<>();
      for (String kw : recent) {
        Double s = redis.opsForZSet().score(zsetKey, kw);
        if (s != null && s > 0) currentCounts.put(kw, Math.round(s));
      }
      if (currentCounts.isEmpty()) {
        log.info("[recordHistory] No current counts found for {} active keywords.", recent.size());
        return;
      }

      var hashOps = redis.opsForHash();
      List<Object> keys = new ArrayList<>(currentCounts.keySet());
      List<Object> prevVals = hashOps.multiGet(lastCountsHash, keys);
      Map<String, String> updates = new HashMap<>();

      for (int i = 0; i < keys.size(); i++) {
        String kw = (String) keys.get(i);
        Long nowCount = currentCounts.get(kw);
        Long prev = parseLong(prevVals.get(i));

        if (!Objects.equals(prev, nowCount)) {
          updates.put(kw, nowCount.toString());
          String histKey = "trends:history:" + kw;
          redis.opsForList().leftPush(histKey, nowCount.toString());
          redis.opsForList().trim(histKey, 0, historyWindow - 1);
          try { redis.expire(histKey, Duration.ofSeconds(historyTtlSeconds)); } catch (Exception ignored) {}
        }
      }
      if (!updates.isEmpty()) {
        hashOps.putAll(lastCountsHash, updates);
        log.info("[recordHistory] Updated {} keywords' history.", updates.size());
      } else {
        log.info("[recordHistory] No keyword history updates needed.");
      }
    } catch (Exception e) {
      log.error("Error in recordHistory task: {}", e.getMessage());
    } finally {
      long ms = Duration.between(start, Instant.now()).toMillis();
      log.info("[recordHistory] Scheduler finished in {} ms", ms);
    }
  }

  // --- TASK 2: THE ANALYST (Slower, Heavy Math) ---
  // Checks for anomalies every 15 seconds (configurable). Uses Lock.
  @Scheduled(fixedDelayString = "${pulse.scheduler.detection-interval-ms}")
  public void detectAnomalies() {
    Instant start = Instant.now();
    String token = UUID.randomUUID().toString();
    Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(lockTtlMs));
    if (Boolean.FALSE.equals(acquired)) {
      log.info("[detectAnomalies] Skipped: another instance is running.");
      return;
    }

    int checked = 0;
    try {
      log.info("[detectAnomalies] Scheduler started at {}", start);
      this.schedulerRuns.increment();
      Timer.Sample sample = Timer.start(metrics);
      try {
        long nowSec = Instant.now().getEpochSecond();
        Set<String> recent = redis.opsForZSet().rangeByScore(activityZsetKey,
                nowSec - activityHorizonSeconds, Double.POSITIVE_INFINITY);
        if (recent != null && !recent.isEmpty()) {
           for (String kw : recent) {
             checkSingleKeyword(kw);
             checked++;
           }
        }
        log.info("[detectAnomalies] Checked {} keywords for anomalies.", checked);
      } finally {
        sample.stop(this.schedulerDuration);
      }
    } catch (Exception e) {
      log.error("Error in detectAnomalies task: {}", e.getMessage());
    } finally {
      String cur = redis.opsForValue().get(lockKey);
      if (token.equals(cur)) redis.delete(lockKey);
      long ms = Duration.between(start, Instant.now()).toMillis();
      log.info("[detectAnomalies] Scheduler finished in {} ms", ms);
    }
  }

  private void checkSingleKeyword(String kw) {
    // Fetch History (Populated by Task 1)
    List<String> historyStrs = redis.opsForList().range("trends:history:" + kw, 0, -1);
    if (historyStrs == null || historyStrs.size() < minSamples) return;

    List<Long> history = historyStrs.stream()
        .map(this::parseLong).filter(Objects::nonNull).toList();
    
    if (history.size() < minSamples) return;

    Long currentCount = history.get(0); // Newest value is at index 0

    // Exclude current sample from baseline statistics
    if (history.size() < 2) return;
    List<Long> baseline = history.subList(1, history.size());
    if (baseline.size() < 2) return; // Need variance
    
    Stats stats = computeStats(baseline);

    if (stats.mean() < baselineVolumeMin) {
      anomaliesSuppressedLowBaseline.increment();
      return;
    }
    if (stats.stddev() <= 0.0) return;

    double z = (currentCount - stats.mean()) / stats.stddev();
    
    String meanStr = String.format("%.2f", stats.mean());
    String stdStr = String.format("%.2f", stats.stddev());
    String zStr = String.format("%.2f", z);
    log.info("Anomaly check: kw='{}' curr={} mean={} std={} z={}", kw, currentCount, meanStr, stdStr, zStr);

    if (shouldEmit(kw, z)) {
      log.info("Anomaly emitted: kw='{}' z={}", kw, zStr);
      emitAnomaly(kw, currentCount, stats, z, Instant.now());
    }
  }

  // Periodic pruning of lastSeen so it doesn’t grow unbounded
  @Scheduled(fixedDelayString = "${pulse.maintenance.activity-trim-interval-ms:60000}")
  public void trimActivityZset() {
    long cutoffSec = Instant.now().getEpochSecond() - activityRetentionSeconds;
    Long removed = redis.opsForZSet()
        .removeRangeByScore(activityZsetKey, Double.NEGATIVE_INFINITY, cutoffSec);
    if (removed != null && removed > 0) {
        log.debug("activity trim removed={}", removed);
    }
  }

  private Long parseLong(Object obj) {
    if (obj instanceof String s) {
      try { return Long.valueOf(s); } catch (NumberFormatException ignored) {}
    } else if (obj instanceof Number n) {
      return n.longValue();
    }
    return null;
  }

  private Stats computeStats(List<Long> history) {
    int n = history.size();
    if (n == 0) return new Stats(0.0, 0.0);
    double sum = 0.0;
    for (Long c : history) sum += c;
    double mean = sum / n;
    if (n == 1) return new Stats(mean, 0.0); 
    double var = 0.0;
    for (Long c : history) {
      double d = c - mean;
      var += d * d;
    }
    // sample standard deviation (n-1)
    double stddev = Math.sqrt(var / (n - 1));
    return new Stats(mean, stddev);
  }

  private boolean shouldEmit(String keyword, double zNow) {
    String lastZKey = "anomaly:last_emitted_z:" + keyword;
    String lastZStr = redis.opsForValue().get(lastZKey);
    Double zPrev = lastZStr != null ? Double.valueOf(lastZStr) : null;

    boolean crossed = (zPrev == null || zPrev < zThreshold) && zNow >= zThreshold;
    boolean stepped = (zPrev != null) && (zNow >= zThreshold) && ((zNow - zPrev) >= minZStep);
    boolean eligible = crossed || stepped;

    if (eligible) {
        redis.opsForValue().set(lastZKey, Double.toString(zNow), Duration.ofSeconds(lastZTtlSeconds));
        return true;
    }
    return false;
  }

  private void emitAnomaly(String keyword, long currentCount, Stats stats, double z, Instant now) {
    AnomalyEvent ev = new AnomalyEvent();
    ev.setKeyword(keyword);
    ev.setCurrentCount(currentCount);
    ev.setAverageCount(stats.mean());
    ev.setStddev(stats.stddev());
    ev.setZScore(z);
    ev.setDetectedAt(now);
    ev.setWindowStart(now.minusSeconds(historyWindow * 10L));
    ev.setWindowEnd(now);

    try {
      anomalyRepo.save(ev);
    } catch (DataIntegrityViolationException ignore) {
      return;
    }

    GenericData.Record record = new GenericData.Record(anomalySchema);
    record.put("keyword", keyword);
    record.put("current_count", (int) Math.min(currentCount, Integer.MAX_VALUE));
    record.put("average_count", stats.mean());
    record.put("stddev", stats.stddev());
    record.put("z_score", z);
    record.put("timestamp", now.toEpochMilli());
    record.put("window_start", now.minusSeconds(historyWindow * 10L).toEpochMilli());
    record.put("window_end", now.toEpochMilli());
    record.put("metadata", null);

    try {
      kafka.send(anomalyTopic, keyword, record);
      anomaliesEmitted.increment();
    } catch (Exception ex) {
      log.debug("Kafka anomaly publish failed (non-fatal): {}", ex.getMessage());
    }
  }

  private record Stats(double mean, double stddev) {}
}