package com.pulse.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "anomalies")
public class DbAnomalyEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String keyword;

  @Column(nullable = false)
  private long currentCount;

  @Column(nullable = false)
  private double averageCount;

  @Column(nullable = false)
  private double stddev;

  @Column(nullable = false)
  private double zScore;

  @Column(nullable = false)
  private Instant detectedAt;

  private Instant windowStart;
  private Instant windowEnd;

  public Long getId() { return id; }
  public String getKeyword() { return keyword; }
  public long getCurrentCount() { return currentCount; }
  public double getAverageCount() { return averageCount; }
  public double getStddev() { return stddev; }
  public double getZScore() { return zScore; }
  public Instant getDetectedAt() { return detectedAt; }
  public Instant getWindowStart() { return windowStart; }
  public Instant getWindowEnd() { return windowEnd; }
}
