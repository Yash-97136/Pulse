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
import org.springframework.data.redis.core.ZSetOperations;
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
  private final String zsetKey;                 // cumulative counts (unchanged)
  private final String anomalyTopic;
  private final double zThreshold;
  private final long cooldownSeconds;
  private final double minZStep;
  private final long lastZTtlSeconds; // TTL for last Z per keyword (seconds)
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

  // NEW: time-based candidate scanning + retention
  private final String activityZsetKey;        // trends:lastSeen
  private final long activityHorizonSeconds;   // scan last N seconds each tick
  private final long activityRetentionSeconds; // keep lastSeen for 24h
  private final long activityTrimIntervalMs;   // how often to trim lastSeen

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
                                 // NEW params
                                 @Value("${pulse.trends.activity-zset-key:trends:lastSeen}") String activityZsetKey,
                                 @Value("${pulse.anomalies.activity-horizon-seconds:60}") long activityHorizonSeconds,
                                 @Value("${pulse.anomalies.activity-retention-seconds:86400}") long activityRetentionSeconds,
                                 @Value("${pulse.maintenance.activity-trim-interval-ms:60000}") long activityTrimIntervalMs,
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

    // NEW assigns
    this.activityZsetKey = activityZsetKey;
    this.activityHorizonSeconds = activityHorizonSeconds;
    this.activityRetentionSeconds = activityRetentionSeconds;
    this.activityTrimIntervalMs = activityTrimIntervalMs;
  }

  private Schema loadSchema(String path) {
    try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(path))) {
      return new Schema.Parser().parse(in);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load Avro schema: " + path, e);
    }
  }

  @Scheduled(fixedDelayString = "${pulse.scheduler.interval-ms}")
  public void tick() {
    log.debug("Scheduler tick fired");
    String token = UUID.randomUUID().toString();
    Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(lockTtlMs));
    if (acquired == null || !acquired) {
      log.debug("Scheduler tick: lock not acquired (another instance running)");
      return;
    }
    try {
      log.info("Scheduler: acquired lock, running anomaly detection");
      this.schedulerRuns.increment();
      Timer.Sample sample = Timer.start(metrics);
      try {
        runOnce();
      } finally {
        sample.stop(this.schedulerDuration);
      }
    } finally {
      String cur = redis.opsForValue().get(lockKey);
      if (token.equals(cur)) redis.delete(lockKey);
    }
  }

  private void runOnce() {
    Instant now = Instant.now();
    long nowSec = now.getEpochSecond();

    // TIME-BASED CANDIDATES: keywords active within the last horizon seconds
    Set<String> recent = Optional.ofNullable(
        redis.opsForZSet().rangeByScore(activityZsetKey,
            nowSec - activityHorizonSeconds,
            Double.POSITIVE_INFINITY)
    ).orElseGet(Set::of);
    if (recent.isEmpty()) {
      log.debug("No recently active keywords in the last {}s", activityHorizonSeconds);
      return;
    }

    // Resolve current cumulative counts from zsetKey for each candidate
    Map<String, Long> current = new HashMap<>();
    for (String kw : recent) {
      Double s = redis.opsForZSet().score(zsetKey, kw);
      if (s != null && s > 0) current.put(kw, Math.round(s));
    }
    if (current.isEmpty()) return;

    // Dedupe: only process changed keywords (vs lastCountsHash)
    var hashOps = redis.opsForHash();
    List<Object> keys = current.keySet().stream().map(Object.class::cast).toList();
    List<Object> prevVals = hashOps.multiGet(lastCountsHash, keys);

    List<String> changed = new ArrayList<>();
    Map<Object, Object> toUpdateHash = new HashMap<>();
    log.info("Checking keywords={} prevValues={} currentCounts={}", keys, prevVals, current);
    for (int i = 0; i < keys.size(); i++) {
      String kw = (String) keys.get(i);
      Long nowCount = current.get(kw);
      Long prev = parseLong(prevVals.get(i));
      if (!Objects.equals(prev, nowCount)) {
        changed.add(kw);
        toUpdateHash.put(kw, nowCount.toString());
        // Store count in history (newest first)
        String histKey = "trends:history:" + kw;
        redis.opsForList().leftPush(histKey, nowCount.toString());
        redis.opsForList().trim(histKey, 0, historyWindow - 1);
        try { redis.expire(histKey, Duration.ofSeconds(historyTtlSeconds)); } catch (Exception ignored) {}
      }
    }
    if (changed.isEmpty()){
      log.debug("No keyword count changes; skipping");
      return;
    }
    hashOps.putAll(lastCountsHash, toUpdateHash);

    // Anomaly detection for changed keywords (unchanged logic)
    for (String kw : changed) {
      Long nowCount = current.get(kw);
      List<String> historyStrs = redis.opsForList().range("trends:history:" + kw, 0, -1);
      if (historyStrs == null || historyStrs.size() < minSamples) continue;

      List<Long> history = historyStrs.stream()
          .map(s -> { try { return Long.valueOf(s); } catch (Exception e) { return null; } })
          .filter(Objects::nonNull)
          .toList();
      if (history.size() < minSamples) continue;

      // Exclude current sample from baseline
      if (history.size() < 2) continue;
      List<Long> baseline = history.subList(1, history.size());
      if (baseline.size() < 2) continue;
      Stats stats = computeStats(baseline);

      if (stats.mean() < baselineVolumeMin) {
        anomaliesSuppressedLowBaseline.increment();
        continue;
      }
      if (stats.stddev() <= 0.0) continue;

      double z = (nowCount - stats.mean()) / stats.stddev();
      log.info("Anomaly check: kw='{}' curr={} baselineMean={} baselineStd={} z={}",
          kw, nowCount, String.format("%.2f", stats.mean()),
          String.format("%.2f", stats.stddev()), String.format("%.2f", z));
      if (shouldEmit(kw, z)) {
        log.info("Anomaly emitted: kw='{}'", kw);
        emitAnomaly(kw, nowCount, stats, z, now);
      }
    }
  }

  // Periodic pruning of lastSeen so it doesn’t grow unbounded (24h retention)
  @Scheduled(fixedDelayString = "${pulse.maintenance.activity-trim-interval-ms:60000}")
  public void trimActivityZset() {
    long cutoffSec = Instant.now().getEpochSecond() - activityRetentionSeconds;
    Long removed = redis.opsForZSet()
        .removeRangeByScore(activityZsetKey, Double.NEGATIVE_INFINITY, cutoffSec);
    log.debug("activity trim removed={}", removed == null ? 0 : removed);
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
    if (n == 1) return new Stats(mean, 0.0); // single point: no variance
    double var = 0.0;
    for (Long c : history) {
      double d = c - mean;
      var += d * d;
    }
    // sample standard deviation (n-1) for small baseline windows
    double stddev = Math.sqrt(var / (n - 1));
    return new Stats(mean, stddev);
  }

  private boolean shouldEmit(String keyword, double zNow) {
    // Get the last emitted Z-score
    String lastZKey = "anomaly:last_emitted_z:" + keyword;
    String lastZStr = redis.opsForValue().get(lastZKey);
    Double zPrev = lastZStr != null ? Double.valueOf(lastZStr) : null;

    // Check eligibility: crossed threshold OR stepped significantly
    boolean crossed = (zPrev == null || zPrev < zThreshold) && zNow >= zThreshold;
    boolean stepped = (zPrev != null) && (zNow >= zThreshold) && ((zNow - zPrev) >= minZStep);
    boolean eligible = crossed || stepped;

    if (!eligible) {
        log.debug("Not eligible to emit for {} zNow={} zPrev={} (no cross/step)", keyword, zNow, zPrev);
        return false;
    }

    // Update last emitted Z with TTL (prevents unbounded growth)
    redis.opsForValue().set(lastZKey, Double.toString(zNow), Duration.ofSeconds(lastZTtlSeconds));
    
    log.info("Eligible to emit for {} zNow={} zPrev={}", keyword, zNow, zPrev);
    return true;
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
      // In single-VM Redis-only mode, Kafka may be absent. Persisting to DB is sufficient.
      log.debug("Kafka anomaly publish failed (non-fatal): {}", ex.getMessage());
    }
    log.info("Anomaly: kw='{}' curr={} mean={} std={} z={}",
        keyword, currentCount, String.format("%.2f", stats.mean()),
        String.format("%.2f", stats.stddev()), String.format("%.2f", z));
  }

  private record Stats(double mean, double stddev) {}
}