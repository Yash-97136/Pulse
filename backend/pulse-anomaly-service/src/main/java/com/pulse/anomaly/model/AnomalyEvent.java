package com.pulse.anomaly.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
  name = "anomalies",
  indexes = {
    @Index(name = "idx_anomalies_detected_at", columnList = "detectedAt DESC"),
    @Index(name = "idx_anomalies_keyword_detected_at", columnList = "keyword,detectedAt DESC")
  },
  uniqueConstraints = {
    @UniqueConstraint(name = "uq_anomaly_kw_window", columnNames = {"keyword","windowStart","windowEnd"})
  }
)
public class AnomalyEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false) private String keyword;
  @Column(nullable = false) private long currentCount;
  @Column(nullable = false) private double averageCount;
  @Column(nullable = false) private double stddev;
  @Column(nullable = false) private double zScore;
  @Column(nullable = false) private Instant detectedAt;
  private Instant windowStart;
  private Instant windowEnd;

  // getters/setters
  public Long getId() { return id; }
  public String getKeyword() { return keyword; }
  public void setKeyword(String keyword) { this.keyword = keyword; }
  public long getCurrentCount() { return currentCount; }
  public void setCurrentCount(long currentCount) { this.currentCount = currentCount; }
  public double getAverageCount() { return averageCount; }
  public void setAverageCount(double averageCount) { this.averageCount = averageCount; }
  public double getStddev() { return stddev; }
  public void setStddev(double stddev) { this.stddev = stddev; }
  public double getZScore() { return zScore; }
  public void setZScore(double zScore) { this.zScore = zScore; }
  public Instant getDetectedAt() { return detectedAt; }
  public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
  public Instant getWindowStart() { return windowStart; }
  public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
  public Instant getWindowEnd() { return windowEnd; }
  public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
}