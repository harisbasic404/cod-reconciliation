package com.example.codreconciliation.repository;

import java.util.List;

import com.example.codreconciliation.entity.ReconciliationItem;
import com.example.codreconciliation.enums.ReconciliationOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, Long> {
    List<ReconciliationItem> findByBatchIdOrderByRowNumAsc(Long batchId);
    List<ReconciliationItem> findByTrackingNumberOrderByBatchUploadedAtDesc(String trackingNumber);
    long countByOutcome(ReconciliationOutcome outcome);
}
