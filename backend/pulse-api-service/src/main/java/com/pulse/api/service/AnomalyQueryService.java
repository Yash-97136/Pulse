package com.pulse.api.service;

import com.pulse.api.model.AnomaliesResponse;
import com.pulse.api.model.AnomalyEvent;
import com.pulse.api.repo.DbAnomalyEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class AnomalyQueryService {

  private final DbAnomalyEventRepository repo;
  private final TrendsService trends;

  public AnomalyQueryService(ObjectProvider<DbAnomalyEventRepository> repoProvider,
                             TrendsService trends) {
    this.repo = repoProvider.getIfAvailable();
    this.trends = trends;
  }

  public AnomaliesResponse latest(int page, int limit, String keyword, Double minZ, Instant since) {
    if (repo == null) {
      // If DB/JPA isn't configured, return 0 for anomaliesToday and empty list (never a stub)
      return new AnomaliesResponse(List.of(), new AnomaliesResponse.Meta(0, 60));
    }

    int size = Math.max(1, Math.min(limit, 200));
    var pageable = PageRequest.of(Math.max(0, page), size);
    List<com.pulse.api.entity.DbAnomalyEvent> rows;
    if (keyword == null && minZ == null && since == null) {
      rows = repo.findAllByOrderByDetectedAtDesc(pageable);
    } else {
      Specification<com.pulse.api.entity.DbAnomalyEvent> spec = buildSpec(keyword, minZ, since);
      rows = repo.findAll(spec, pageable).getContent();
    }

    List<AnomalyEvent> events = rows.stream().map(row -> new AnomalyEvent(
        String.valueOf(row.getId()),
        row.getKeyword(),
        row.getZScore(),
        Math.round(row.getAverageCount()),
        row.getCurrentCount(),
        row.getDetectedAt()
    )).toList();

    // Robust UTC-based anomaliesToday count
    Instant nowUtc = Instant.now();
    Instant startOfDayUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    long todayCount = repo.countByDetectedAtBetween(startOfDayUtc, nowUtc);
    // Optionally: log.debug("Counting anomalies from {} to {}: {}", startOfDayUtc, nowUtc, todayCount);
    return new AnomaliesResponse(events, new AnomaliesResponse.Meta((int) todayCount, 60));
  }

  private Specification<com.pulse.api.entity.DbAnomalyEvent> buildSpec(String keyword, Double minZ, Instant since) {
    return (root, query, cb) -> {
      var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
      if (keyword != null && !keyword.isBlank()) {
        predicates.add(cb.like(cb.lower(root.get("keyword")), "%" + keyword.toLowerCase() + "%"));
      }
      if (minZ != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("zScore"), minZ));
      }
      if (since != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("detectedAt"), since));
      }
      query.orderBy(cb.desc(root.get("detectedAt")));
      return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
    };
  }
}
