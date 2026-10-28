package com.example.codreconciliation.service;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HexFormat;

import com.example.codreconciliation.dto.ParsedRow;
import com.example.codreconciliation.dto.BatchResponse;
import com.example.codreconciliation.dto.ReconciliationItemResponse;
import com.example.codreconciliation.dto.ReconciliationResult;
import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.entity.ReconciliationBatch;
import com.example.codreconciliation.entity.ReconciliationItem;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.enums.ReconciliationOutcome;
import com.example.codreconciliation.enums.HistoryOutcomeFilter;
import com.example.codreconciliation.repository.OrderRepository;
import com.example.codreconciliation.repository.ReconciliationBatchRepository;
import com.example.codreconciliation.exception.ResourceNotFoundException;
import com.example.codreconciliation.exception.DuplicateReportException;
import com.example.codreconciliation.exception.ExcelParsingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@Service
public class ReconciliationService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final DateTimeFormatter EXPORT_TIMESTAMP =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private final ExcelParserService parser;
    private final OrderRepository orderRepository;
    private final ReconciliationBatchRepository batchRepository;

    public ReconciliationService(ExcelParserService parser, OrderRepository orderRepository,
                                 ReconciliationBatchRepository batchRepository) {
        this.parser = parser;
        this.orderRepository = orderRepository;
        this.batchRepository = batchRepository;
    }

    @Transactional
    public ReconciliationResult reconcile(MultipartFile file) {
        validateFileSizeBeforeHashing(file);
        String contentHash = contentHash(file);
        if (batchRepository.existsByContentHash(contentHash)) {
            throw new DuplicateReportException("This reconciliation report has already been processed.");
        }
        List<ParsedRow> rows = parser.parse(file);
        ReconciliationBatch batch = new ReconciliationBatch(safeFileName(file.getOriginalFilename()));
        batch.setContentHash(contentHash);
        Set<String> seenTrackingNumbers = new HashSet<>();
        int successful = 0;
        int mismatches = 0;
        int notFound = 0;
        int errors = 0;

        for (ParsedRow row : rows) {
            ReconciliationItem item;
            if (!row.isValid()) {
                item = errorItem(row, String.join(" ", row.errors()));
                errors++;
            } else if (!seenTrackingNumbers.add(normalizeTracking(row.trackingNumber()))) {
                item = errorItem(row, "Duplicate tracking number in this report; first occurrence was processed.");
                errors++;
            } else {
                Order order = orderRepository.findByTrackingNumberIgnoreCase(row.trackingNumber()).orElse(null);
                if (order == null) {
                    item = new ReconciliationItem(row.rowNumber(), row.trackingNumber(), null,
                            row.collectedAmount(), row.deliveryDate(), ReconciliationOutcome.NOT_FOUND,
                            "No matching order exists for tracking number " + row.trackingNumber() + ".");
                    notFound++;
                } else if (order.getExpectedAmount().compareTo(row.collectedAmount()) == 0) {
                    order.setStatus(OrderStatus.RECONCILED);
                    item = new ReconciliationItem(row.rowNumber(), row.trackingNumber(), order.getExpectedAmount(),
                            row.collectedAmount(), row.deliveryDate(), ReconciliationOutcome.RECONCILED,
                            "Collected amount matches the expected amount.");
                    successful++;
                } else {
                    order.setStatus(OrderStatus.AMOUNT_MISMATCH);
                    BigDecimal difference = row.collectedAmount().subtract(order.getExpectedAmount());
                    item = new ReconciliationItem(row.rowNumber(), row.trackingNumber(), order.getExpectedAmount(),
                            row.collectedAmount(), row.deliveryDate(), ReconciliationOutcome.AMOUNT_MISMATCH,
                            "Amount mismatch: collected " + row.collectedAmount()
                                    + ", expected " + order.getExpectedAmount() + ", difference " + difference + ".");
                    mismatches++;
                }

            }
            batch.addItem(item);
        }

        batch.setCounts(rows.size(), successful, mismatches, notFound, errors);
        ReconciliationBatch saved;
        try {
            saved = batchRepository.saveAndFlush(batch);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateReportException("This reconciliation report has already been processed.");
        }
        return new ReconciliationResult(saved.getId(), saved.getFileName(), saved.getUploadedAt(),
                saved.getTotalRows(), saved.getSuccessfulMatches(), saved.getMismatches(),
                saved.getNotFound(), saved.getErrors());
    }

    @Transactional(readOnly = true)
    public BatchResponse getBatch(Long id) {
        ReconciliationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation batch " + id + " was not found."));
        return toResponse(batch);
    }

    @Transactional(readOnly = true)
    public List<BatchResponse> getHistory() {
        return batchRepository.findAllByOrderByUploadedAtDesc().stream().map(this::toSummaryResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<BatchResponse> searchHistory(String search, LocalDate from, LocalDate to,
                                             HistoryOutcomeFilter outcome) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' date must not be after 'to' date.");
        }
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String normalized = search == null || search.isBlank() ? null : search.trim();
        return batchRepository.searchHistory(normalized, fromInstant, toInstant,
                        outcome == null ? null : outcome.name())
                .stream().map(this::toSummaryResponse).toList();
    }

    @Transactional(readOnly = true)
    public ExportedBatch export(Long id) {
        ReconciliationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation batch " + id + " was not found."));
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeSummarySheet(workbook, batch);
            writeResultsSheet(workbook, batch);
            workbook.write(output);
            return new ExportedBatch(output.toByteArray(), exportFileName(batch));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not generate the reconciliation Excel export.", ex);
        }
    }

    private void writeSummarySheet(Workbook workbook, ReconciliationBatch batch) {
        Sheet sheet = workbook.createSheet("Summary");
        CellStyle labelStyle = labelStyle(workbook);
        CellStyle titleStyle = titleStyle(workbook);
        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("COD Reconciliation Report");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
        String[][] metadata = {
                {"Batch ID", String.valueOf(batch.getId())},
                {"Source filename", batch.getFileName()},
                {"Uploaded at (UTC)", EXPORT_TIMESTAMP.format(batch.getUploadedAt())},
                {"Total rows", String.valueOf(batch.getTotalRows())},
                {"Reconciled", String.valueOf(batch.getSuccessfulMatches())},
                {"Amount mismatches", String.valueOf(batch.getMismatches())},
                {"Not found", String.valueOf(batch.getNotFound())},
                {"Errors", String.valueOf(batch.getErrors())}
        };
        for (int i = 0; i < metadata.length; i++) {
            Row row = sheet.createRow(i + 2);
            Cell label = row.createCell(0);
            label.setCellValue(metadata[i][0]);
            label.setCellStyle(labelStyle);
            if (i == 0 || i >= 3) {
                row.createCell(1).setCellValue(Integer.parseInt(metadata[i][1]));
            } else {
                row.createCell(1).setCellValue(metadata[i][1]);
            }
        }
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 48 * 256);
    }

    private void writeResultsSheet(Workbook workbook, ReconciliationBatch batch) {
        Sheet sheet = workbook.createSheet("Results");
        String[] headers = {"Original row", "Tracking number", "Expected amount", "Collected amount",
                "Delivery date", "Outcome", "Explanation"};
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle moneyStyle = workbook.createCellStyle();
        moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        int rowIndex = 1;
        for (ReconciliationItem item : batch.getItems()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(item.getRowNum());
            setText(row.createCell(1), item.getTrackingNumber());
            setAmount(row.createCell(2), item.getExpectedAmount(), moneyStyle);
            setAmount(row.createCell(3), item.getCollectedAmount(), moneyStyle);
            if (item.getDeliveryDate() != null) {
                Cell date = row.createCell(4);
                date.setCellValue(item.getDeliveryDate());
                date.setCellStyle(dateStyle);
            }
            setText(row.createCell(5), item.getOutcome().name());
            setText(row.createCell(6), item.getMessage());
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.length - 1));
        int[] widths = {14, 24, 18, 18, 16, 22, 72};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        return style;
    }

    private CellStyle labelStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void setText(Cell cell, String value) {
        if (value != null) cell.setCellValue(value);
    }

    private void setAmount(Cell cell, BigDecimal value, CellStyle style) {
        if (value != null) {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        }
    }

    private String exportFileName(ReconciliationBatch batch) {
        String base = safeFileName(batch.getFileName());
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (base.toLowerCase(Locale.ROOT).endsWith(".xlsx")) base = base.substring(0, base.length() - 5);
        return "reconciliation-" + batch.getId() + "-" + base + ".xlsx";
    }

    private BatchResponse toResponse(ReconciliationBatch batch) {
        List<ReconciliationItemResponse> items = batch.getItems().stream()
                .map(item -> new ReconciliationItemResponse(item.getId(), item.getRowNum(),
                        item.getTrackingNumber(), item.getExpectedAmount(), item.getCollectedAmount(),
                        item.getDeliveryDate(), item.getOutcome(), item.getMessage()))
                .toList();
        return new BatchResponse(batch.getId(), batch.getFileName(), batch.getUploadedAt(),
                batch.getTotalRows(), batch.getSuccessfulMatches(), batch.getMismatches(),
                batch.getNotFound(), batch.getErrors(), items);
    }

    private BatchResponse toSummaryResponse(ReconciliationBatch batch) {
        return new BatchResponse(batch.getId(), batch.getFileName(), batch.getUploadedAt(),
                batch.getTotalRows(), batch.getSuccessfulMatches(), batch.getMismatches(),
                batch.getNotFound(), batch.getErrors(), List.of());
    }

    private ReconciliationItem errorItem(ParsedRow row, String message) {
        return new ReconciliationItem(row.rowNumber(), row.trackingNumber(), null,
                row.collectedAmount(), row.deliveryDate(), ReconciliationOutcome.ERROR, message);
    }

    private String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) return "uploaded-report.xlsx";
        String normalized = originalName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isBlank()) return "uploaded-report.xlsx";
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String contentHash(MultipartFile file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the uploaded reconciliation report.", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private void validateFileSizeBeforeHashing(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ExcelParsingException("The uploaded file is missing or empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ExcelParsingException("The uploaded file exceeds the 10 MB limit.");
        }
    }

    private String normalizeTracking(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record ExportedBatch(byte[] content, String fileName) {
    }
}
