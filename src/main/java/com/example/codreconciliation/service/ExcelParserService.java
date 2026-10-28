package com.example.codreconciliation.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.codreconciliation.dto.ParsedRow;
import com.example.codreconciliation.exception.ExcelParsingException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExcelParserService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    };
    private static final Pattern GROUPED_INTEGER = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("\\d+(?:[.,]\\d{1,2})");

    public List<ParsedRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ExcelParsingException("The uploaded file is missing or empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ExcelParsingException("The uploaded file exceeds the 10 MB limit.");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ExcelParsingException("Only .xlsx courier reports are supported.");
        }
        try (InputStream input = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(input)) {
            return parseWorkbook(workbook);
        } catch (ExcelParsingException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ExcelParsingException("The workbook is unreadable or corrupt.", ex);
        }
    }

    private List<ParsedRow> parseWorkbook(Workbook workbook) {
        if (workbook.getNumberOfSheets() == 0) {
            throw new ExcelParsingException("The workbook has no worksheets.");
        }
        Sheet sheet = workbook.getSheetAt(0);
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new ExcelParsingException("The first worksheet has no header row.");
        }
        Map<String, Integer> columns = resolveHeaders(header, new DataFormatter(Locale.ROOT));
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<ParsedRow> rows = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            ParsedRow parsed = parseRow(row, index + 1, columns, evaluator, formatter);
            if (parsed.hasData()) {
                rows.add(parsed);
            }
        }
        if (rows.isEmpty()) {
            throw new ExcelParsingException("The worksheet contains no usable data rows.");
        }
        return rows;
    }

    private Map<String, Integer> resolveHeaders(Row header, DataFormatter formatter) {
        Map<String, Set<String>> aliases = Map.of(
                "tracking", Set.of("tracking number", "tracking no", "tracking id",
                        "broj posiljke", "broj za pracenje"),
                "amount", Set.of("collected amount", "cod amount", "collected cod",
                        "iznos otkupnine", "naplaceni iznos"),
                "date", Set.of("delivery date", "delivered date", "date delivered",
                        "datum isporuke", "datum dostave"));
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String actual = normalize(formatter.formatCellValue(header.getCell(i)));
            for (Map.Entry<String, Set<String>> entry : aliases.entrySet()) {
                if (entry.getValue().contains(actual) && columns.put(entry.getKey(), i) != null) {
                    throw new ExcelParsingException("Ambiguous header for required field: " + entry.getKey() + ".");
                }
            }
        }
        for (String required : aliases.keySet()) {
            if (!columns.containsKey(required)) {
                String label = switch (required) {
                    case "tracking" -> "Tracking Number";
                    case "amount" -> "Collected Amount";
                    default -> "Delivery Date";
                };
                throw new ExcelParsingException("Missing required header: " + label + ".");
            }
        }
        return columns;
    }

    private ParsedRow parseRow(Row row, int rowNumber, Map<String, Integer> columns,
                               FormulaEvaluator evaluator, DataFormatter formatter) {
        if (row == null || isEmpty(row, columns.values(), formatter, evaluator)) {
            return new ParsedRow(rowNumber, null, null, null, List.of());
        }
        List<String> errors = new ArrayList<>();
        String tracking = null;
        try {
            tracking = readText(row.getCell(columns.get("tracking")), evaluator, formatter);
        } catch (RuntimeException ex) {
            errors.add("Invalid tracking number.");
        }
        if (tracking != null) {
            tracking = tracking.trim();
            if (tracking.isEmpty()) {
                tracking = null;
            } else if (tracking.length() > 64) {
                errors.add("Tracking number exceeds 64 characters.");
            } else if (!tracking.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
                errors.add("Tracking number contains unsupported characters.");
            }
        }
        if (tracking == null) {
            errors.add("Tracking number is required.");
        }
        BigDecimal amount = null;
        try {
            amount = readAmount(row.getCell(columns.get("amount")), evaluator, formatter);
            if (amount == null) {
                errors.add("Collected amount is required.");
            } else if (amount.signum() < 0) {
                errors.add("Collected amount cannot be negative.");
            }
        } catch (RuntimeException ex) {
            errors.add(ex.getMessage() == null ? "Invalid collected amount." : ex.getMessage());
        }
        LocalDate date = null;
        try {
            date = readDate(row.getCell(columns.get("date")), evaluator, formatter);
            if (date == null) {
                errors.add("Delivery date is required.");
            }
        } catch (RuntimeException ex) {
            errors.add("Invalid delivery date.");
        }
        return new ParsedRow(rowNumber, tracking, amount, date, List.copyOf(errors));
    }

    private boolean isEmpty(Row row, Iterable<Integer> columns, DataFormatter formatter,
                            FormulaEvaluator evaluator) {
        for (Integer column : columns) {
            Cell cell = row.getCell(column);
            if (cell != null) {
                try {
                    if (!formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                        return false;
                    }
                } catch (RuntimeException ex) {
                    return false;
                }
            }
        }
        return true;
    }

    private String readText(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null) return null;
        String value = formatter.formatCellValue(cell, evaluator);
        return value == null ? null : value;
    }

    private BigDecimal readAmount(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null || formatter.formatCellValue(cell, evaluator).trim().isEmpty()) return null;
        Cell evaluated = cell;
        if (cell.getCellType() == CellType.FORMULA) {
            evaluated = evaluator.evaluateInCell(cell);
        }
        if (evaluated.getCellType() == CellType.NUMERIC) {
            BigDecimal amount = numericAmount(evaluated);
            return validateScale(amount);
        }
        return parseTextAmount(formatter.formatCellValue(evaluated, evaluator).trim());
    }

    private BigDecimal numericAmount(Cell cell) {
        if (cell instanceof XSSFCell xssfCell && xssfCell.getRawValue() != null
                && !xssfCell.getRawValue().isBlank()) {
            return new BigDecimal(xssfCell.getRawValue());
        }
        return BigDecimal.valueOf(cell.getNumericCellValue());
    }

    private BigDecimal parseTextAmount(String value) {
        if (value.isEmpty()) return null;
        boolean negative = value.startsWith("-");
        if (negative) value = value.substring(1);
        if (value.contains("+") || value.contains("-") || value.contains(" ")) {
            throw new IllegalArgumentException("Invalid collected amount.");
        }
        long dots = value.chars().filter(character -> character == '.').count();
        long commas = value.chars().filter(character -> character == ',').count();
        String normalized;
        if (dots > 0 && commas > 0) {
            int decimalIndex = Math.max(value.lastIndexOf('.'), value.lastIndexOf(','));
            String integerPart = value.substring(0, decimalIndex);
            String fractionPart = value.substring(decimalIndex + 1);
            char groupingSeparator = value.charAt(decimalIndex) == '.' ? ',' : '.';
            if (!fractionPart.matches("\\d{1,2}")
                    || !integerPart.matches("\\d{1,3}(?:"
                    + Pattern.quote(String.valueOf(groupingSeparator)) + "\\d{3})+")) {
                throw new IllegalArgumentException("Invalid collected amount.");
            }
            normalized = integerPart.replace(String.valueOf(groupingSeparator), "") + "." + fractionPart;
        } else if (dots == 1 || commas == 1) {
            char separator = dots == 1 ? '.' : ',';
            int separatorIndex = value.indexOf(separator);
            String integerPart = value.substring(0, separatorIndex);
            String fractionPart = value.substring(separatorIndex + 1);
            if (DECIMAL_NUMBER.matcher(value).matches() && fractionPart.length() <= 2) {
                normalized = integerPart + "." + fractionPart;
            } else if (GROUPED_INTEGER.matcher(value).matches() && fractionPart.length() == 3) {
                throw new IllegalArgumentException("Ambiguous collected amount.");
            } else {
                throw new IllegalArgumentException("Invalid collected amount.");
            }
        } else if (value.matches("\\d+")) {
            normalized = value;
        } else {
            throw new IllegalArgumentException("Invalid collected amount.");
        }
        BigDecimal amount = validateScale(new BigDecimal(normalized));
        return negative ? amount.negate() : amount;
    }

    private BigDecimal validateScale(BigDecimal amount) {
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Collected amount must have at most two decimal places.");
        }
        return amount.setScale(2);
    }

    private LocalDate readDate(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null || formatter.formatCellValue(cell, evaluator).trim().isEmpty()) return null;
        Cell evaluated = cell;
        if (cell.getCellType() == CellType.FORMULA) evaluated = evaluator.evaluateInCell(cell);
        if (evaluated.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(evaluated)) {
            return evaluated.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = formatter.formatCellValue(evaluated, evaluator).trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next documented format.
            }
        }
        throw new IllegalArgumentException("Unsupported date");
    }

    private String normalize(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return decomposed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
