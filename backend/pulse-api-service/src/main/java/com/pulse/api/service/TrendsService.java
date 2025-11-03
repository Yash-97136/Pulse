package com.pulse.api.service;

import com.pulse.api.model.AnomaliesResponse;
import com.pulse.api.model.AnomalyEvent;
import com.pulse.api.model.KeywordDetailResponse;
import com.pulse.api.model.TrendMetric;
import com.pulse.api.model.TrendsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class TrendsService {

  private final StringRedisTemplate redis;
  private final String zsetKey;
  private final String activityZsetKey;

  public TrendsService(StringRedisTemplate redis,
                       @Value("${pulse.trends.zset-key}") String zsetKey,
                       @Value("${pulse.trends.activity-zset-key:}") String activityZsetKey) {
    this.redis = redis;
    this.zsetKey = zsetKey;
    this.activityZsetKey = activityZsetKey;
  }

  public TrendsResponse topRange(int offset, int limit) {
    Set<ZSetOperations.TypedTuple<String>> tuples = null;
    Long zcard = null;
    try {
      tuples = redis.opsForZSet().reverseRangeWithScores(zsetKey, offset, Math.max(offset, offset + limit - 1));
      zcard = redis.opsForZSet().zCard(zsetKey);
    } catch (Exception e) {
      // Redis not available; fall back to empty list
    }

    List<TrendMetric> metrics = new ArrayList<>();
    long totalPosts = 0;
    if (tuples != null) {
      for (ZSetOperations.TypedTuple<String> tuple : tuples) {
        if (tuple == null || tuple.getValue() == null) {
          continue;
        }
        long volume = Math.round(tuple.getScore() == null ? 0.0 : tuple.getScore());
        totalPosts += volume;
        metrics.add(buildTrendMetric(tuple.getValue(), volume));
      }
    }

    Long activeKeywords = null;
    try {
      // Target semantics: keywords active in the last windowMinutes (default 60m)
      long windowMinutes = 60;
      long windowStartMillis = Instant.now().minus(Duration.ofMinutes(windowMinutes)).toEpochMilli();

      if (activityZsetKey != null && !activityZsetKey.isBlank()) {
        // activityZsetKey stores lastSeenAt (epoch millis) as the score
        // Use ZCOUNT [windowStart, +inf]. Keep zero if there are none.
        Long count = redis.opsForZSet().count(activityZsetKey, (double) windowStartMillis, Double.POSITIVE_INFINITY);
        if (count != null) activeKeywords = count; // accept 0 as a valid value
      }

      // Fallbacks only if Redis not available or activity key missing
      if (activeKeywords == null) activeKeywords = (long) metrics.size();
      if (activeKeywords == null) activeKeywords = zcard;
    } catch (Exception ignored) {}

    int nextOffset = offset + metrics.size();
    boolean hasMore = zcard != null && nextOffset < zcard;

    // Safety: if global zset is empty (e.g., after a reset) but activity zset still has entries,
    // cap activeKeywords by total tracked keywords to avoid inconsistent KPIs.
    if (zcard != null && activeKeywords != null) {
      activeKeywords = Math.min(activeKeywords, zcard);
    }

    return new TrendsResponse(
        metrics,
    new TrendsResponse.Meta(
            totalPosts,
            60,
            Instant.now(),
            activeKeywords,
            zcard,
            nextOffset,
            hasMore
        )
    );
  }

  public KeywordDetailResponse keywordDetail(String keyword) {
    long volume = 0;
    try {
      Double score = redis.opsForZSet().score(zsetKey, keyword);
      volume = score == null ? 0 : Math.round(score);
    } catch (Exception e) {
      // Redis not available; use default volume 0
    }

    List<KeywordDetailResponse.TrendPoint> series = buildTrendSeries(volume);

    return new KeywordDetailResponse(
        keyword,
        "No description available",
        series,
        List.of(
            new KeywordDetailResponse.RelatedPost(
                keyword + "-sample-1",
                "Sample post for keyword '" + keyword + "'",
                "twitter",
                Instant.now().minusSeconds(1_800),
                null
            ),
            new KeywordDetailResponse.RelatedPost(
                keyword + "-sample-2",
                "Another mention for '" + keyword + "'",
                "reddit",
                Instant.now().minusSeconds(3_600),
                null
            )
        ),
        new KeywordDetailResponse.KeywordAnalytics(
            volume,
            series.isEmpty() ? 0.0 : Math.min(99.0, volume / 10.0),
            volume,
            series.isEmpty() ? 0.0 : Math.max(0, series.get(series.size() - 1).value() - series.get(0).value())
        )
    );
  }

  public AnomaliesResponse stubAnomalies(int limit) {
    List<AnomalyEvent> events = new ArrayList<>();
    Instant now = Instant.now();
    for (int i = 0; i < limit; i++) {
      events.add(new AnomalyEvent(
          "anomaly-" + (i + 1),
          "keyword-" + (i + 1),
          2.5 + (i * 0.15),
          100 + (i * 12L),
          160 + (i * 15L),
          now.minusSeconds(i * 600L)
      ));
    }

    return new AnomaliesResponse(events, new AnomaliesResponse.Meta(events.size(), 60));
  }

  private TrendMetric buildTrendMetric(String keyword, long volume) {
    List<Long> sparkline = buildSparkline(volume);
    return new TrendMetric(
        keyword,
        volume,
        sparkline.isEmpty() ? 0 : Math.max(0, sparkline.get(sparkline.size() - 1) - sparkline.get(0)),
        volume,
        sparkline,
        60,
        Instant.now(),
        null
    );
  }

  private List<Long> buildSparkline(long volume) {
    if (volume <= 0) {
      return List.of(0L, 0L, 0L, 0L, 0L);
    }
    long base = Math.max(1, volume / 5);
    return List.of(
        base,
        Math.max(base, base + (long) (base * 0.2)),
        Math.max(base, base + (long) (base * 0.35)),
        Math.max(base, base + (long) (base * 0.5)),
        volume
    );
  }

  private List<KeywordDetailResponse.TrendPoint> buildTrendSeries(long volume) {
    List<Long> sparkline = buildSparkline(volume);
    List<KeywordDetailResponse.TrendPoint> series = new ArrayList<>();
    Instant now = Instant.now();
    for (int i = 0; i < sparkline.size(); i++) {
      series.add(new KeywordDetailResponse.TrendPoint(
          now.minusSeconds((long) (sparkline.size() - 1 - i) * 900L),
          sparkline.get(i)
      ));
    }
    return series;
  }
}