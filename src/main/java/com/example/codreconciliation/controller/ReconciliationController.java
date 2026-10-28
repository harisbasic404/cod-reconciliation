package com.example.codreconciliation.controller;

import java.util.List;
import java.time.LocalDate;

import com.example.codreconciliation.dto.BatchResponse;
import com.example.codreconciliation.service.ReconciliationService;
import com.example.codreconciliation.service.PdfExportService;
import com.example.codreconciliation.enums.HistoryOutcomeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/reconcile")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final PdfExportService pdfExportService;

    public ReconciliationController(ReconciliationService reconciliationService, PdfExportService pdfExportService) {
        this.reconciliationService = reconciliationService;
        this.pdfExportService = pdfExportService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse upload(@RequestParam("file") MultipartFile file) {
        return reconciliationService.getBatch(reconciliationService.reconcile(file).batchId());
    }

    @GetMapping("/history")
    public List<BatchResponse> history(@RequestParam(required = false) String search,
                                       @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to,
                                       @RequestParam(required = false) HistoryOutcomeFilter outcome) {
        return reconciliationService.searchHistory(search, from, to, outcome);
    }

    @GetMapping("/history/{id}")
    public BatchResponse historyItem(@PathVariable Long id) {
        return reconciliationService.getBatch(id);
    }

    @GetMapping("/history/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        ReconciliationService.ExportedBatch export = reconciliationService.export(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(export.fileName()).build());
        headers.setContentLength(export.content().length);
        return ResponseEntity.ok().headers(headers).body(export.content());
    }

    @GetMapping("/history/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        PdfExportService.ExportedPdf export = pdfExportService.reconciliation(id);
        return pdfResponse(export);
    }

    private ResponseEntity<byte[]> pdfResponse(PdfExportService.ExportedPdf export) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(export.fileName()).build());
        headers.setContentLength(export.content().length);
        return ResponseEntity.ok().headers(headers).body(export.content());
    }
}
