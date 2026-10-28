package com.example.codreconciliation.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.entity.ReconciliationBatch;
import com.example.codreconciliation.entity.ReconciliationItem;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.enums.ReconciliationOutcome;
import com.example.codreconciliation.repository.OrderRepository;
import com.example.codreconciliation.repository.ReconciliationBatchRepository;
import com.example.codreconciliation.repository.ReconciliationItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PersistenceIntegrationTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private ReconciliationBatchRepository batchRepository;
    @Autowired private ReconciliationItemRepository itemRepository;

    @Test
    void createsSeedOrdersOnlyOnce() {
        assertThat(orderRepository.count()).isEqualTo(5);
        assertThat(orderRepository.findByTrackingNumber("TRK-1001")).isPresent();
        assertThat(orderRepository.findByStatus(OrderStatus.PENDING)).hasSize(5);
    }

    @Test
    void trackingNumbersAreUnique() {
        Order duplicate = new Order("TRK-1001", "Another Customer", new BigDecimal("1.00"), OrderStatus.PENDING);
        assertThatThrownBy(() -> orderRepository.saveAndFlush(duplicate))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @Transactional
    void persistsBatchItemsAndReloadsRelationship() {
        ReconciliationBatch batch = new ReconciliationBatch("courier-report.xlsx");
        batch.setCounts(1, 1, 0, 0, 0);
        batch.addItem(new ReconciliationItem(2, "TRK-1001", new BigDecimal("149.90"),
                new BigDecimal("149.90"), LocalDate.of(2026, 10, 25),
                ReconciliationOutcome.RECONCILED, "Matched"));
        Long batchId = batchRepository.saveAndFlush(batch).getId();

        ReconciliationBatch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertThat(reloaded.getItems()).singleElement()
                .extracting(ReconciliationItem::getTrackingNumber)
                .isEqualTo("TRK-1001");
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(batchId)).hasSize(1);
    }
}
