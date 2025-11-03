package com.pulse.api.model;

import java.util.List;

public record AnomaliesResponse(List<AnomalyEvent> anomalies, Meta meta) {
  public record Meta(int anomaliesToday, Integer windowMinutes) {}
}
