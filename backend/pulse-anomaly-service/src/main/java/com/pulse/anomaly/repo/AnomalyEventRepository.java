package com.pulse.anomaly.repo;

import com.pulse.anomaly.model.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {}