package com.pulse.api.model;

import java.time.Instant;
import java.util.List;

public record TrendsResponse(
    List<TrendMetric> trends,
    Meta meta
) {
  public record Meta(
      long totalPosts,
      Integer windowMinutes,
      Instant generatedAt,
      Long activeKeywords,
      Long totalKeywords,
      Integer nextOffset,
      Boolean hasMore
  ) {}
}
