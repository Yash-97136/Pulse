package com.pulse.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class PulseProcessingApplication {
  public static void main(String[] args) {
    SpringApplication.run(PulseProcessingApplication.class, args);
  }
}