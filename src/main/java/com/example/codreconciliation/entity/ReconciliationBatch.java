package com.example.codreconciliation.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "reconciliation_batches")
public class ReconciliationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_hash", length = 64, unique = true)
    private String contentHash;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "successful_matches", nullable = false)
    private int successfulMatches;

    @Column(nullable = false)
    private int mismatches;

    @Column(nullable = false)
    private int notFound;

    @Column(nullable = false)
    private int errors;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ReconciliationItem> items = new ArrayList<>();

    protected ReconciliationBatch() {
    }

    public ReconciliationBatch(String fileName) {
        this.fileName = fileName;
        this.uploadedAt = Instant.now();
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public void addItem(ReconciliationItem item) {
        items.add(item);
        item.assignBatch(this);
    }

    public Long getId() { return id; }
    public String getFileName() { return fileName; }
    public String getContentHash() { return contentHash; }
    public Instant getUploadedAt() { return uploadedAt; }
    public int getTotalRows() { return totalRows; }
    public int getSuccessfulMatches() { return successfulMatches; }
    public int getMismatches() { return mismatches; }
    public int getNotFound() { return notFound; }
    public int getErrors() { return errors; }
    public List<ReconciliationItem> getItems() { return List.copyOf(items); }
    public void setCounts(int totalRows, int successfulMatches, int mismatches, int notFound, int errors) {
        this.totalRows = totalRows;
        this.successfulMatches = successfulMatches;
        this.mismatches = mismatches;
        this.notFound = notFound;
        this.errors = errors;
    }
}
