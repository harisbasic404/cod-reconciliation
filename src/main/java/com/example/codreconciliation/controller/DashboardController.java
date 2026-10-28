package com.example.codreconciliation.controller;

import com.example.codreconciliation.dto.DashboardStatsResponse;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.enums.ReconciliationOutcome;
import com.example.codreconciliation.repository.OrderRepository;
import com.example.codreconciliation.repository.ReconciliationBatchRepository;
import com.example.codreconciliation.repository.ReconciliationItemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final OrderRepository orders;
    private final ReconciliationBatchRepository batches;
    private final ReconciliationItemRepository items;

    public DashboardController(OrderRepository orders, ReconciliationBatchRepository batches,
                               ReconciliationItemRepository items) {
        this.orders = orders;
        this.batches = batches;
        this.items = items;
    }

    @GetMapping("/stats")
    public DashboardStatsResponse stats() {
        return new DashboardStatsResponse(orders.count(), orders.countByStatus(OrderStatus.PENDING),
                orders.countByStatus(OrderStatus.RECONCILED), orders.countByStatus(OrderStatus.AMOUNT_MISMATCH),
                batches.count(), batches.sumTotalRows(),
                items.countByOutcome(ReconciliationOutcome.RECONCILED),
                items.countByOutcome(ReconciliationOutcome.AMOUNT_MISMATCH),
                items.countByOutcome(ReconciliationOutcome.NOT_FOUND),
                items.countByOutcome(ReconciliationOutcome.ERROR));
    }
}
