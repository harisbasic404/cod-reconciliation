package com.example.codreconciliation.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.codreconciliation.dto.OrderImportError;
import com.example.codreconciliation.dto.OrderImportPreviewResponse;
import com.example.codreconciliation.dto.OrderImportResponse;
import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.exception.ExcelParsingException;
import com.example.codreconciliation.exception.ImportConflictException;
import com.example.codreconciliation.repository.OrderRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class OrderImportService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    private final OrderRepository orderRepository;
    private final Map<UUID, StoredPreview> previews = new ConcurrentHashMap<>();

    public OrderImportService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderImportPreviewResponse preview(MultipartFile file) {
        ParsedImport parsed = parse(file);
        UUID id = UUID.randomUUID();
        previews.put(id, new StoredPreview(Instant.now(), parsed.candidates()));
        purgeExpired();
        return new OrderImportPreviewResponse(id, parsed.totalRows(), parsed.validRows(),
                parsed.errors().size(), parsed.duplicateRows(), parsed.existingRows(), parsed.errors());
    }

    @Transactional
    public OrderImportResponse confirm(UUID importId) {
        StoredPreview stored = previews.remove(importId);
        if (stored == null || stored.createdAt().plus(PREVIEW_TTL).isBefore(Instant.now())) {
            throw new ImportConflictException("The import preview is missing or has expired. Preview the file again.");
        }
        for (Candidate candidate : stored.candidates()) {
            if (orderRepository.existsByTrackingNumberIgnoreCase(candidate.trackingNumber())) {
                throw new ImportConflictException("Tracking number '" + candidate.trackingNumber()
                        + "' was added after preview. No orders were imported; preview the file again.");
            }
        }
        List<Order> orders = stored.candidates().stream()
                .map(candidate -> new Order(candidate.trackingNumber(), candidate.customerName(),
                        candidate.expectedAmount(), OrderStatus.PENDING))
                .toList();
        try {
            orderRepository.saveAllAndFlush(orders);
        } catch (DataIntegrityViolationException ex) {
            throw new ImportConflictException("The import could not be completed because a tracking number "
                    + "already exists. No orders were imported; preview the file again.");
        }
        return new OrderImportResponse(orders.size());
    }

    private ParsedImport parse(MultipartFile file) {
        validateFile(file);
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ExcelParsingException("The workbook has no worksheets.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) {
                throw new ExcelParsingException("The first worksheet has no header row.");
            }
            Map<String, Integer> columns = columns(header);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<Candidate> candidates = new ArrayList<>();
            List<OrderImportError> errors = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int total = 0;
            int duplicates = 0;
            int existing = 0;
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || empty(row, columns.values(), formatter, evaluator)) {
                    continue;
                }
                total++;
                int rowNumber = index + 1;
                String tracking = text(row.getCell(columns.get("tracking")), formatter, evaluator);
                String customer = text(row.getCell(columns.get("customer")), formatter, evaluator);
                BigDecimal amount = amount(row.getCell(columns.get("amount")), formatter, evaluator);
                List<String> rowErrors = new ArrayList<>();
                tracking = trimToNull(tracking);
                customer = trimToNull(customer);
                if (tracking == null) rowErrors.add("Tracking number is required.");
                else if (tracking.length() > 64 || !tracking.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
                    rowErrors.add("Tracking number contains unsupported characters.");
                }
                if (customer == null) rowErrors.add("Customer name is required.");
                if (amount == null) rowErrors.add("Expected amount is required and must be a number.");
                else if (amount.signum() <= 0) rowErrors.add("Expected amount must be greater than zero.");
                String key = tracking == null ? null : tracking.toUpperCase(Locale.ROOT);
                if (key != null && !seen.add(key)) {
                    duplicates++;
                    rowErrors.add("Duplicate tracking number in this file.");
                } else if (key != null && orderRepository.existsByTrackingNumberIgnoreCase(tracking)) {
                    existing++;
                    rowErrors.add("Tracking number already exists in the database.");
                }
                if (rowErrors.isEmpty()) {
                    candidates.add(new Candidate(tracking, customer, amount));
                } else {
                    errors.add(new OrderImportError(rowNumber, tracking, String.join(" ", rowErrors)));
                }
            }
            if (total == 0) throw new ExcelParsingException("The worksheet contains no usable data rows.");
            return new ParsedImport(total, candidates.size(), duplicates, existing, candidates, errors);
        } catch (ExcelParsingException ex) {
            throw ex;
        } catch (IOException | EncryptedDocumentException ex) {
            throw new ExcelParsingException("The workbook is unreadable or corrupt.", ex);
        }
    }

    private Map<String, Integer> columns(Row header) {
        Map<String, Set<String>> aliases = Map.of(
                "tracking", Set.of("tracking number", "tracking no", "tracking id"),
                "customer", Set.of("customer name", "name", "kupac", "ime kupca"),
                "amount", Set.of("expected amount", "cod amount", "amount", "iznos otkupnine"));
        Map<String, Integer> result = new HashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String value = normalize(formatter.formatCellValue(header.getCell(i)));
            for (Map.Entry<String, Set<String>> alias : aliases.entrySet()) {
                if (alias.getValue().contains(value)) {
                    if (result.put(alias.getKey(), i) != null) {
                        throw new ExcelParsingException("Ambiguous header for required field: " + alias.getKey() + ".");
                    }
                }
            }
        }
        for (String required : aliases.keySet()) {
            if (!result.containsKey(required)) {
                throw new ExcelParsingException("Missing required header: " + required + ".");
            }
        }
        return result;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ExcelParsingException("The uploaded file is missing or empty.");
        if (file.getSize() > MAX_FILE_SIZE) throw new ExcelParsingException("The uploaded file exceeds the 10 MB limit.");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ExcelParsingException("Only .xlsx order files are supported.");
        }
    }

    private boolean empty(Row row, Iterable<Integer> columns, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Integer column : columns) if (!text(row.getCell(column), formatter, evaluator).isBlank()) return false;
        return true;
    }

    private String text(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator);
    }

    private BigDecimal amount(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = text(cell, formatter, evaluator).trim();
        if (value.isEmpty()) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalize(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return decomposed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(PREVIEW_TTL);
        previews.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    private record Candidate(String trackingNumber, String customerName, BigDecimal expectedAmount) { }
    private record ParsedImport(int totalRows, int validRows, int duplicateRows, int existingRows,
                                List<Candidate> candidates, List<OrderImportError> errors) { }
    private record StoredPreview(Instant createdAt, List<Candidate> candidates) { }
}
