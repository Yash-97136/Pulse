package com.pulse.api.model;

import java.time.Instant;

public record AnomalyEvent(
    String id,
    String keyword,
    double zScore,
    long baselineVolume,
    long currentVolume,
    Instant createdAt
) {}
