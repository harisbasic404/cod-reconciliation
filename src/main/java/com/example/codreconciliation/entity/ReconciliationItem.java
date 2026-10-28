package com.example.codreconciliation.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.codreconciliation.enums.ReconciliationOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "reconciliation_items")
public class ReconciliationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ReconciliationBatch batch;

    @Column(name = "row_num", nullable = false)
    private int rowNum;

    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    @Column(name = "expected_amount", precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "collected_amount", precision = 15, scale = 2)
    private BigDecimal collectedAmount;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReconciliationOutcome outcome;

    @Column(length = 500)
    private String message;

    protected ReconciliationItem() {
    }

    public ReconciliationItem(int rowNumber, String trackingNumber, BigDecimal expectedAmount,
                              BigDecimal collectedAmount, LocalDate deliveryDate,
                              ReconciliationOutcome outcome, String message) {
        this.rowNum = rowNumber;
        this.trackingNumber = trackingNumber;
        this.expectedAmount = expectedAmount;
        this.collectedAmount = collectedAmount;
        this.deliveryDate = deliveryDate;
        this.outcome = outcome;
        this.message = message;
    }

    void assignBatch(ReconciliationBatch batch) {
        this.batch = batch;
    }

    public Long getId() { return id; }
    public ReconciliationBatch getBatch() { return batch; }
    public int getRowNum() { return rowNum; }
    public String getTrackingNumber() { return trackingNumber; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public BigDecimal getCollectedAmount() { return collectedAmount; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public ReconciliationOutcome getOutcome() { return outcome; }
    public String getMessage() { return message; }
}
