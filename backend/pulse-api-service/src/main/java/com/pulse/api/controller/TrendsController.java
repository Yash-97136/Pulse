package com.pulse.api.controller;

import com.pulse.api.model.AnomaliesResponse;
import com.pulse.api.model.KeywordDetailResponse;
import com.pulse.api.model.TrendsResponse;
import com.pulse.api.service.AnomalyQueryService;
import com.pulse.api.service.TrendsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrendsController {

  private final TrendsService trends;
  private final AnomalyQueryService anomalies;

  public TrendsController(TrendsService trends, AnomalyQueryService anomalies) {
    this.trends = trends;
    this.anomalies = anomalies;
  }

  @GetMapping("/api/trends")
  public TrendsResponse getTrends(
      @RequestParam(name = "offset", defaultValue = "0") int offset,
      @RequestParam(name = "limit", defaultValue = "20") int limit
  ) {
    int n = Math.max(1, Math.min(limit, 100));
    int off = Math.max(0, offset);
    return trends.topRange(off, n);
  }

  @GetMapping("/api/trends/{keyword}")
  public ResponseEntity<KeywordDetailResponse> getTrendDetail(@PathVariable("keyword") String keyword) {
    KeywordDetailResponse detail = trends.keywordDetail(keyword);
    if (detail == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(detail);
  }


}