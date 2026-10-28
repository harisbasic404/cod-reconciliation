package com.example.codreconciliation.service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.codreconciliation.entity.ReconciliationItem;
import com.example.codreconciliation.entity.ReconciliationBatch;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.enums.ReconciliationOutcome;
import com.example.codreconciliation.repository.OrderRepository;
import com.example.codreconciliation.repository.ReconciliationBatchRepository;
import com.example.codreconciliation.repository.ReconciliationItemRepository;
import com.example.codreconciliation.service.OrderService;
import com.example.codreconciliation.dto.OrderRequest;
import com.example.codreconciliation.dto.ParsedRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class ReconciliationServiceTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private ExcelParserService parser;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ReconciliationBatchRepository batchRepository;
    @Autowired private ReconciliationItemRepository itemRepository;
    @Autowired private OrderService orderService;
    @SpyBean private ReconciliationBatchRepository batchRepositorySpy;

    @Test
    void reconcilesMatchesMismatchesUnknownsInvalidRowsAndDuplicates() {
        MockMultipartFile file = workbook(
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)},
                new Object[]{"TRK-1002", 80.00, LocalDate.of(2026, 10, 20)},
                new Object[]{"UNKNOWN", 12.50, LocalDate.of(2026, 10, 20)},
                new Object[]{"", 10.00, LocalDate.of(2026, 10, 20)},
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)},
                new Object[]{"TRK-1003", "bad amount", "bad date"},
                new Object[]{null, null, null},
                new Object[]{"TRK-1004", 120.50, LocalDate.of(2026, 10, 21)});

        var result = reconciliationService.reconcile(file);

        assertThat(result.totalRows()).isEqualTo(7);
        assertThat(result.successfulMatches()).isEqualTo(2);
        assertThat(result.mismatches()).isEqualTo(1);
        assertThat(result.notFound()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(3);
        assertThat(result.successfulMatches() + result.mismatches()
                + result.notFound() + result.errors()).isEqualTo(result.totalRows());
        var persistedItems = itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId());
        assertThat(persistedItems).hasSize(result.totalRows());
        assertThat(persistedItems.stream().filter(item -> item.getOutcome() == ReconciliationOutcome.RECONCILED).count())
                .isEqualTo(result.successfulMatches());
        assertThat(persistedItems.stream().filter(item -> item.getOutcome() == ReconciliationOutcome.AMOUNT_MISMATCH).count())
                .isEqualTo(result.mismatches());
        assertThat(persistedItems.stream().filter(item -> item.getOutcome() == ReconciliationOutcome.NOT_FOUND).count())
                .isEqualTo(result.notFound());
        assertThat(persistedItems.stream().filter(item -> item.getOutcome() == ReconciliationOutcome.ERROR).count())
                .isEqualTo(result.errors());
        assertThat(orderRepository.findByTrackingNumber("TRK-1001").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.RECONCILED);
        assertThat(orderRepository.findByTrackingNumber("TRK-1002").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.AMOUNT_MISMATCH);
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId()))
                .extracting(ReconciliationItem::getOutcome)
                .containsExactly(ReconciliationOutcome.RECONCILED, ReconciliationOutcome.AMOUNT_MISMATCH,
                        ReconciliationOutcome.NOT_FOUND, ReconciliationOutcome.ERROR,
                        ReconciliationOutcome.ERROR, ReconciliationOutcome.ERROR,
                        ReconciliationOutcome.RECONCILED);
    }

    @Test
    void preservesOrdersAbsentFromReport() {
        var before = orderRepository.findByTrackingNumber("TRK-1005").orElseThrow().getStatus();
        reconciliationService.reconcile(workbook(
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)}));
        assertThat(orderRepository.findByTrackingNumber("TRK-1005").orElseThrow().getStatus()).isEqualTo(before);
    }

    @Test
    void editingAnOrderDoesNotChangeRecordedReconciliationHistory() {
        ReconciliationBatch batch = new ReconciliationBatch("history-before-edit.xlsx");
        ReconciliationItem item = new ReconciliationItem(1, "TRK-1005", new BigDecimal("55.00"),
                new BigDecimal("55.00"), LocalDate.of(2026, 10, 22),
                ReconciliationOutcome.RECONCILED, "Recorded expected amount");
        batch.setCounts(1, 1, 0, 0, 0);
        batch.addItem(item);
        batchRepository.saveAndFlush(batch);

        orderService.update(5L, new OrderRequest("TRK-1005", "Edited Customer", new BigDecimal("88.00")));

        assertThat(itemRepository.findById(item.getId()).orElseThrow().getExpectedAmount())
                .isEqualByComparingTo("55.00");
    }

    @Test
    void processedHistoryKeepsOriginalAmountsAfterOrderUpdate() {
        orderRepository.saveAndFlush(new com.example.codreconciliation.entity.Order(
                "HISTORY-INTEGRITY", "History Customer", new BigDecimal("17.50"), OrderStatus.PENDING));

        var result = reconciliationService.reconcile(workbook(
                new Object[]{"HISTORY-INTEGRITY", 17.50, LocalDate.of(2026, 10, 20)}));

        orderService.update(orderRepository.findByTrackingNumberIgnoreCase("HISTORY-INTEGRITY")
                        .orElseThrow().getId(),
                new OrderRequest("HISTORY-INTEGRITY", "Updated History Customer", new BigDecimal("22.00")));

        ReconciliationItem item = itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId())
                .get(0);
        assertThat(item.getExpectedAmount()).isEqualByComparingTo("17.50");
        assertThat(item.getCollectedAmount()).isEqualByComparingTo("17.50");
        assertThat(item.getOutcome()).isEqualTo(ReconciliationOutcome.RECONCILED);
    }

    @Test
    void rejectsMissingHeadersAndCorruptFiles() {
        MockMultipartFile missingHeaders = workbookWithHeaders("Tracking Number", "Wrong", "Delivery Date");
        assertThatThrownBy(() -> parser.parse(missingHeaders))
                .hasMessageContaining("Collected Amount");

        MockMultipartFile corrupt = new MockMultipartFile("file", "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not an xlsx".getBytes());
        assertThatThrownBy(() -> parser.parse(corrupt))
                .hasMessageContaining("unreadable or corrupt");
    }

    @Test
    void rejectsEmptyFilesAndEmptyWorksheets() {
        MockMultipartFile empty = new MockMultipartFile("file", "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        assertThatThrownBy(() -> parser.parse(empty))
                .hasMessageContaining("missing or empty");

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            MockMultipartFile noSheets = new MockMultipartFile("file", "report.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
            assertThatThrownBy(() -> parser.parse(noSheets))
                    .hasMessageContaining("no worksheets");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void recordsMissingAmountAndInvalidDateWithoutDroppingFollowingValidRow() {
        List<?> rows = parser.parse(workbook(
                new Object[]{"MISSING-AMOUNT", null, LocalDate.of(2026, 10, 20)},
                new Object[]{"INVALID-DATE", 10.00, "not-a-date"},
                new Object[]{null, null, null},
                new Object[]{"VALID-AFTER", 11.00, LocalDate.of(2026, 10, 20)}));

        assertThat(rows).hasSize(3);
        assertThat(((ParsedRow) rows.get(0)).errors()).contains("Collected amount is required.");
        assertThat(((ParsedRow) rows.get(1)).errors()).contains("Invalid delivery date.");
        assertThat(((ParsedRow) rows.get(2)).isValid()).isTrue();
    }

    @Test
    void supportsExcelDateCellsAndStringDates() {
        List<?> rows = parser.parse(workbook(
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)}));
        assertThat(rows).hasSize(1);
        assertThat(((com.example.codreconciliation.dto.ParsedRow) rows.get(0)).deliveryDate())
                .isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void parsesSupportedTextAmountSeparators() {
        List<?> rows = parser.parse(workbook(
                new Object[]{"A", "1234.56", LocalDate.of(2026, 10, 20)},
                new Object[]{"B", "1234,56", LocalDate.of(2026, 10, 20)},
                new Object[]{"C", "1.234,56", LocalDate.of(2026, 10, 20)},
                new Object[]{"D", "1,234.56", LocalDate.of(2026, 10, 20)}));

        assertThat(rows.stream().map(value -> ((ParsedRow) value).collectedAmount()).toList())
                .containsExactly(new BigDecimal("1234.56"), new BigDecimal("1234.56"),
                        new BigDecimal("1234.56"), new BigDecimal("1234.56"));
    }

    @Test
    void keepsInvalidAmountAsRowErrorAndContinuesWithFollowingRows() {
        var result = reconciliationService.reconcile(workbook(
                new Object[]{"UNKNOWN-DECIMAL", "10.001", LocalDate.of(2026, 10, 20)},
                new Object[]{"UNKNOWN-VALID", "1234,56", LocalDate.of(2026, 10, 20)}));

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.notFound()).isEqualTo(1);
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId()))
                .extracting(ReconciliationItem::getOutcome)
                .containsExactly(ReconciliationOutcome.ERROR, ReconciliationOutcome.NOT_FOUND);
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId()).get(0).getMessage())
                .isEqualTo("Ambiguous collected amount.");
    }

    @Test
    void invalidRowDoesNotChangeExistingOrderStatus() {
        orderRepository.saveAndFlush(new com.example.codreconciliation.entity.Order(
                "INVALID-STATUS", "Invalid Row Customer", new BigDecimal("10.00"), OrderStatus.PENDING));

        var result = reconciliationService.reconcile(workbook(
                new Object[]{"INVALID-STATUS", "not-an-amount", LocalDate.of(2026, 10, 20)}));

        assertThat(result.errors()).isEqualTo(1);
        assertThat(orderRepository.findByTrackingNumberIgnoreCase("INVALID-STATUS")
                .orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void treatsNumericAmountWithMoreThanTwoDecimalsAsRowError() {
        List<?> rows = parser.parse(workbook(
                new Object[]{"UNKNOWN-NUMERIC", 10.001, LocalDate.of(2026, 10, 20)},
                new Object[]{"UNKNOWN-AFTER", 11.00, LocalDate.of(2026, 10, 20)}));

        assertThat(rows).hasSize(2);
        assertThat(((ParsedRow) rows.get(0)).errors())
                .contains("Collected amount must have at most two decimal places.");
        assertThat(((ParsedRow) rows.get(1)).isValid()).isTrue();
    }

    @Test
    void rejectsAmbiguousTextAmountAsRowError() {
        ParsedRow row = (ParsedRow) parser.parse(workbook(
                new Object[]{"UNKNOWN-AMBIGUOUS", "1,234", LocalDate.of(2026, 10, 20)})).get(0);

        assertThat(row.errors()).contains("Ambiguous collected amount.");
    }

    @Test
    void recognizesCourierHeadersRegardlessOfLanguageCaseAndColumnOrder() {
        MockMultipartFile file = workbookWithHeadersAndRows("Datum dostave", "Broj pošiljke",
                "Naplaćeni iznos", new Object[]{LocalDate.of(2026, 10, 20), "TRK-1001", 149.90});
        List<?> rows = parser.parse(file);
        assertThat(rows).hasSize(1);
        var row = (com.example.codreconciliation.dto.ParsedRow) rows.get(0);
        assertThat(row.trackingNumber()).isEqualTo("TRK-1001");
        assertThat(row.collectedAmount()).isEqualByComparingTo("149.90");
        assertThat(row.deliveryDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void matchesTrackingNumbersIgnoringCaseAndOuterWhitespace() {
        var result = reconciliationService.reconcile(workbook(
                new Object[]{"  trk-1001  ", 149.90, LocalDate.of(2026, 10, 20)}));

        assertThat(result.successfulMatches()).isEqualTo(1);
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId()))
                .singleElement().extracting(ReconciliationItem::getOutcome)
                .isEqualTo(ReconciliationOutcome.RECONCILED);
    }

    @Test
    void treatsCaseOnlyTrackingDuplicatesAsOneReportError() {
        var result = reconciliationService.reconcile(workbook(
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)},
                new Object[]{" trk-1001 ", 149.90, LocalDate.of(2026, 10, 20)}));

        assertThat(result.successfulMatches()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(result.batchId()))
                .extracting(ReconciliationItem::getOutcome)
                .containsExactly(ReconciliationOutcome.RECONCILED, ReconciliationOutcome.ERROR);
    }

    @Test
    void rollsBackOrderStatusWhenBatchPersistenceFails() {
        orderRepository.saveAndFlush(new com.example.codreconciliation.entity.Order(
                "TRK-ROLLBACK", "Rollback Customer", new BigDecimal("120.50"), OrderStatus.PENDING));
        long batchesBefore = batchRepository.count();
        long itemsBefore = itemRepository.count();
        doThrow(new IllegalStateException("controlled persistence failure"))
                .when(batchRepositorySpy).saveAndFlush(any(ReconciliationBatch.class));

        assertThatThrownBy(() -> reconciliationService.reconcile(workbook(
                new Object[]{"TRK-ROLLBACK", 120.50, LocalDate.of(2026, 10, 21)})))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orderRepository.findByTrackingNumberIgnoreCase("TRK-ROLLBACK").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(batchRepository.count()).isEqualTo(batchesBefore);
        assertThat(itemRepository.count()).isEqualTo(itemsBefore);
    }

    @Test
    void repeatedIdenticalReportDoesNotCreateAnotherBatch() {
        MockMultipartFile file = workbook(
                new Object[]{"UNKNOWN-REPEAT-INTEGRITY", 9.99, LocalDate.of(2026, 10, 20)});
        long batchesBefore = batchRepository.count();
        var first = reconciliationService.reconcile(file);

        assertThatThrownBy(() -> reconciliationService.reconcile(file))
                .hasMessage("This reconciliation report has already been processed.");

        assertThat(batchRepository.count()).isEqualTo(batchesBefore + 1);
        assertThat(batchRepository.findById(first.batchId())).isPresent();
        assertThat(itemRepository.findByBatchIdOrderByRowNumAsc(first.batchId())).hasSize(1);
    }

    private MockMultipartFile workbook(Object[]... values) {
        return workbookWithHeadersAndRows("Tracking Number", "Collected Amount", "Delivery Date", values);
    }

    private MockMultipartFile workbookWithHeaders(String first, String second, String third) {
        return workbookWithHeadersAndRows(first, second, third);
    }

    private MockMultipartFile workbookWithHeadersAndRows(String first, String second, String third,
                                                         Object[]... values) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Courier Report");
            Row headers = sheet.createRow(0);
            headers.createCell(0).setCellValue(first);
            headers.createCell(1).setCellValue(second);
            headers.createCell(2).setCellValue(third);
            for (int i = 0; i < values.length; i++) {
                Row row = sheet.createRow(i + 1);
                setCell(workbook, row, 0, values[i][0]);
                setCell(workbook, row, 1, values[i][1]);
                setCell(workbook, row, 2, values[i][2]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "courier-report.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private void setCell(XSSFWorkbook workbook, Row row, int column, Object value) {
        if (value == null) return;
        if (value instanceof Number number) row.createCell(column).setCellValue(number.doubleValue());
        else if (value instanceof LocalDate date) {
            var cell = row.createCell(column);
            cell.setCellValue(date);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            cell.setCellStyle(style);
        }
        else row.createCell(column).setCellValue(value.toString());
    }
}
