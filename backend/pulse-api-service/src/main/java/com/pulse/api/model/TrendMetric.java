package com.pulse.api.model;

import java.time.Instant;
import java.util.List;

public record TrendMetric(
    String keyword,
    double score,
    double delta,
    long volume,
    List<Long> sparkline,
    int trendWindowMinutes,
    Instant lastSeenAt,
    Double sentiment
) {}
