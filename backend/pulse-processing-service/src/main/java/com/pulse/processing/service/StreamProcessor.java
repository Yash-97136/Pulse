package com.pulse.processing.service;

import com.pulse.processing.text.Stopwords;
import com.pulse.processing.text.Tokenizer;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class StreamProcessor {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessor.class);

    private final StringRedisTemplate redis;
    private final Tokenizer tokenizer;
    
    @Value("${pulse.trends.activity-zset-key:trends:lastSeen}")
    private String activityZsetKey;

    @Value("${pulse.processing.df-ttl-seconds:86400}")     // rolling window (24h)
    private long dfTtlSeconds;

    @Value("${pulse.processing.df-max-ratio:0.20}")        // suppress tokens in >20% of docs
    private double dfMaxRatio;

    // Maintenance knobs to prevent unbounded Redis growth
    @Value("${pulse.maintenance.max-tokens:100000}")
    private long maxTokens;                                 // cap on trends:global members

    @Value("${pulse.maintenance.activity-ttl-seconds:604800}") // default 7d retention
    private long activityTtlSeconds;

    public StreamProcessor(StringRedisTemplate redis) {
        this.redis = redis;

        // Optional runtime extras from Redis set "trends:stopwords"
        var extras = new HashSet<String>();
        try {
            var members = redis.opsForSet().members("trends:stopwords");
            if (members != null) members.forEach(s -> extras.add(s.toLowerCase()));
        } catch (Exception ignored) {}

        var sw = Stopwords.load(Optional.of("/stopwords-iso-en.txt"), extras);
        this.tokenizer = new Tokenizer(sw, 3, 24);
    }

    @PostConstruct
    void logStopwords() {
        Stopwords sw = Stopwords.load(Optional.of("/stopwords-iso-en.txt"), Set.of());
        log.info("Loaded {} stopwords (ISO + extras)", sw.asSet().size());
    }

    // Call this for each incoming post text
    public void handleMessage(String text) {
        List<String> tokens = tokenizer.tokens(text);
        if (tokens.isEmpty()) return;

        // Unique tokens per document (for document-frequency)
        Set<String> unique = new HashSet<>(tokens);

        // Rolling total docs counter with TTL (approx window)
        String totalKey = "trends:docs_total";
        Long totalDocs = redis.opsForValue().increment(totalKey);
        if (totalDocs != null && (redis.getExpire(totalKey) == -1 || redis.getExpire(totalKey) == -2)) {
            redis.expire(totalKey, Duration.ofSeconds(dfTtlSeconds));
        }

        for (String token : unique) {
            String dfKey = "trends:df:" + token;
            Long df = redis.opsForValue().increment(dfKey);
            if (df != null && (redis.getExpire(dfKey) == -1 || redis.getExpire(dfKey) == -2)) {
                redis.expire(dfKey, Duration.ofSeconds(dfTtlSeconds));
            }

            // If too ubiquitous in the window, skip counting toward trends
            if (df != null && totalDocs != null && totalDocs > 0) {
                double ratio = df.doubleValue() / totalDocs.doubleValue();
                if (ratio > dfMaxRatio) {
                    continue;
                }
            }

            // Count token toward global trends
            redis.opsForZSet().incrementScore("trends:global", token, 1.0);

            // Mark activity: last seen timestamp (epoch seconds) for time-windowed active keyword KPI
            try {
                long nowSec = Instant.now().getEpochSecond();
                redis.opsForZSet().add(activityZsetKey, token, nowSec);
            } catch (Exception ignored) {}
        }
    }

    // Periodic maintenance to prune old/low-scoring entries and aged activity markers
    @Scheduled(fixedDelayString = "${pulse.maintenance.interval-ms:60000}")
    void maintenance() {
        try {
            // 1) Trim activity ZSET by age (epoch seconds) to keep memory bounded
            long cutoff = Instant.now().minusSeconds(activityTtlSeconds).getEpochSecond();
            try {
                Long removed = redis.opsForZSet().removeRangeByScore(activityZsetKey, Double.NEGATIVE_INFINITY, (double) cutoff);
                if (removed != null && removed > 0) {
                    log.debug("Maintenance: pruned {} entries from {} older than {}", removed, activityZsetKey, cutoff);
                }
            } catch (Exception ignored) {}

            // 2) Enforce a maximum size for trends:global by removing the lowest-ranked tail
            Long size = null;
            try { size = redis.opsForZSet().zCard("trends:global"); } catch (Exception ignored) {}
            if (size != null && maxTokens > 0 && size > maxTokens) {
                long toRemove = size - maxTokens;
                try {
                    redis.opsForZSet().removeRange("trends:global", 0, toRemove - 1);
                    log.info("Maintenance: trimmed trends:global from {} to {} (removed {})", size, maxTokens, toRemove);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Maintenance task failed: {}", e.getMessage());
        }
    }
}