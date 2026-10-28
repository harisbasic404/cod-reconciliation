package com.example.codreconciliation.repository;

import java.util.List;
import java.time.Instant;

import com.example.codreconciliation.entity.ReconciliationBatch;
import com.example.codreconciliation.enums.HistoryOutcomeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatch, Long> {
    List<ReconciliationBatch> findAllByOrderByUploadedAtDesc();
    boolean existsByContentHash(String contentHash);

    @Query("""
            select b from ReconciliationBatch b
            where (:search is null or lower(b.fileName) like lower(concat('%', :search, '%')))
              and (:fromDate is null or b.uploadedAt >= :fromDate)
              and (:toDate is null or b.uploadedAt < :toDate)
              and (
                   :outcome is null
                   or (:outcome = 'HAS_MATCHES' and b.successfulMatches > 0)
                   or (:outcome = 'HAS_MISMATCHES' and b.mismatches > 0)
                   or (:outcome = 'HAS_NOT_FOUND' and b.notFound > 0)
                   or (:outcome = 'HAS_ERRORS' and b.errors > 0)
                   or (:outcome = 'PROBLEMS' and (b.mismatches > 0 or b.notFound > 0 or b.errors > 0))
                   or (:outcome = 'SUCCESSFUL_ONLY' and b.mismatches = 0 and b.notFound = 0 and b.errors = 0)
              )
            order by b.uploadedAt desc
            """)
    List<ReconciliationBatch> searchHistory(String search, Instant fromDate, Instant toDate,
                                             String outcome);

    @Query("select coalesce(sum(b.totalRows), 0) from ReconciliationBatch b")
    long sumTotalRows();
}
