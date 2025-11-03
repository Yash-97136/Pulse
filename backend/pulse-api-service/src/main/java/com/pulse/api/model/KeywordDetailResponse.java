package com.pulse.api.model;

import java.time.Instant;
import java.util.List;

public record KeywordDetailResponse(
    String keyword,
    String description,
    List<TrendPoint> trendSeries,
    List<RelatedPost> relatedPosts,
    KeywordAnalytics analytics
) {
  public record TrendPoint(Instant timestamp, long value) {}

  public record RelatedPost(String id, String text, String source, Instant timestamp, String link) {}

  public record KeywordAnalytics(double currentScore, double percentile, long docFrequency, double velocity) {}
}
