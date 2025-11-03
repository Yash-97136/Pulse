package com.pulse.anomaly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PulseAnomalyApplication {
  public static void main(String[] args) {
    SpringApplication.run(PulseAnomalyApplication.class, args);
  }
}