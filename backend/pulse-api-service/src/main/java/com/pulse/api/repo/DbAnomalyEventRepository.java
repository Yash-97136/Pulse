package com.pulse.api.repo;

import com.pulse.api.entity.DbAnomalyEvent;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DbAnomalyEventRepository extends JpaRepository<DbAnomalyEvent, Long>, JpaSpecificationExecutor<DbAnomalyEvent> {
  List<DbAnomalyEvent> findAllByOrderByDetectedAtDesc(Pageable pageable);

  @Query("SELECT e FROM DbAnomalyEvent e " +
      "WHERE (:keyword IS NULL OR LOWER(e.keyword) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
      "AND (:minZ IS NULL OR e.zScore >= :minZ) " +
      "AND (:since IS NULL OR e.detectedAt >= :since) " +
      "ORDER BY e.detectedAt DESC")
  List<DbAnomalyEvent> search(
      @Param("keyword") String keyword,
      @Param("minZ") Double minZ,
      @Param("since") Instant since,
      Pageable pageable);

  long countByDetectedAtBetween(Instant start, Instant end);
}
