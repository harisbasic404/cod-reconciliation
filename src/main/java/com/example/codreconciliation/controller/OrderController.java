package com.example.codreconciliation.controller;

import java.util.List;
import java.util.UUID;

import com.example.codreconciliation.dto.OrderImportPreviewResponse;
import com.example.codreconciliation.dto.OrderImportResponse;
import com.example.codreconciliation.dto.OrderDetailResponse;
import com.example.codreconciliation.dto.OrderRequest;
import com.example.codreconciliation.dto.OrderResponse;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.service.OrderService;
import com.example.codreconciliation.service.OrderImportService;
import com.example.codreconciliation.service.PdfExportService;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderImportService orderImportService;
    private final PdfExportService pdfExportService;

    public OrderController(OrderService orderService, OrderImportService orderImportService,
                           PdfExportService pdfExportService) {
        this.orderService = orderService;
        this.orderImportService = orderImportService;
        this.pdfExportService = pdfExportService;
    }

    @GetMapping
    public List<OrderResponse> getOrders(@RequestParam(required = false) String search,
                                        @RequestParam(required = false) OrderStatus status) {
        return orderService.search(search, status);
    }

    @GetMapping("/{id}")
    public OrderDetailResponse getOrder(@PathVariable Long id) {
        return orderService.get(id);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request));
    }

    @PutMapping("/{id}")
    public OrderResponse updateOrder(@PathVariable Long id, @Valid @RequestBody OrderRequest request) {
        return orderService.update(id, request);
    }

    @PostMapping("/import/preview")
    public OrderImportPreviewResponse previewImport(@RequestPart("file") MultipartFile file) {
        return orderImportService.preview(file);
    }

    @PostMapping("/import/confirm")
    public OrderImportResponse confirmImport(@RequestParam UUID importId) {
        return orderImportService.confirm(importId);
    }

    @GetMapping("/{id}/invoice/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id) {
        PdfExportService.ExportedPdf export = pdfExportService.invoice(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(export.fileName()).build());
        headers.setContentLength(export.content().length);
        return ResponseEntity.ok().headers(headers).body(export.content());
    }
}
