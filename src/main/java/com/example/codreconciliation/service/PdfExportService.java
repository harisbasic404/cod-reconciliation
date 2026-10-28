package com.example.codreconciliation.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.entity.ReconciliationBatch;
import com.example.codreconciliation.entity.ReconciliationItem;
import com.example.codreconciliation.enums.ReconciliationOutcome;
import com.example.codreconciliation.exception.ResourceNotFoundException;
import com.example.codreconciliation.repository.OrderRepository;
import com.example.codreconciliation.repository.ReconciliationBatchRepository;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PdfExportService {

    private static final Color NAVY = new Color(19, 34, 56);
    private static final Color LIGHT = new Color(241, 245, 249);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ReconciliationBatchRepository batchRepository;
    private final OrderRepository orderRepository;

    public PdfExportService(ReconciliationBatchRepository batchRepository, OrderRepository orderRepository) {
        this.batchRepository = batchRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public ExportedPdf reconciliation(Long id) {
        ReconciliationBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation batch " + id + " was not found."));
        try {
            Document document = new Document(PageSize.A4.rotate(), 28, 28, 42, 38);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new FooterEvent());
            document.open();
            Fonts fonts = fonts();
            title(document, "COD Reconciliation System", "COD Reconciliation Report", fonts);
            metadata(document, fonts, new String[][]{
                    {"Batch ID", String.valueOf(batch.getId())},
                    {"Source file", batch.getFileName()},
                    {"Processed at", DATE_TIME.format(batch.getUploadedAt())},
                    {"PDF generated at", DATE_TIME.format(Instant.now())}
            });
            summary(document, batch, fonts);
            document.add(section("Detailed results", fonts.bold));
            PdfPTable table = table(new float[]{.55f, 1.2f, 1f, 1f, 1f, 1f, 1.15f, 3f});
            header(table, fonts, "Excel row", "Tracking", "Expected", "Collected",
                    "Delivery date", "Difference", "Status", "Explanation");
            if (batch.getItems().isEmpty()) {
                PdfPCell empty = cell("No reconciliation results.", fonts.normal, 8);
                empty.setColspan(8);
                table.addCell(empty);
            } else {
                for (ReconciliationItem item : batch.getItems()) {
                    add(table, String.valueOf(item.getRowNum()), fonts);
                    add(table, dash(item.getTrackingNumber()), fonts);
                    add(table, money(item.getExpectedAmount()), fonts);
                    add(table, money(item.getCollectedAmount()), fonts);
                    add(table, item.getDeliveryDate() == null ? "—" : DATE.format(item.getDeliveryDate()), fonts);
                    add(table, difference(item), fonts);
                    add(table, item.getOutcome().name(), fonts);
                    add(table, dash(item.getMessage()), fonts);
                }
            }
            document.add(table);
            document.close();
            return new ExportedPdf(output.toByteArray(), "cod-reconciliation-" + id + ".pdf");
        } catch (DocumentException ex) {
            throw new IllegalStateException("Could not generate the reconciliation PDF.", ex);
        }
    }

    @Transactional(readOnly = true)
    public ExportedPdf invoice(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " was not found."));
        try {
            Document document = new Document(PageSize.A4, 48, 48, 48, 48);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new FooterEvent());
            document.open();
            Fonts fonts = fonts();
            title(document, "COD Reconciliation System", "INVOICE / PREGLED NARUDŽBE", fonts);
            metadata(document, fonts, new String[][]{
                    {"Document number", "ORDER-" + order.getId()},
                    {"Order ID", String.valueOf(order.getId())},
                    {"Issued at", DATE_TIME.format(Instant.now())},
                    {"Document type", "Informational preview; not a fiscal invoice"}
            });
            document.add(section("Seller", fonts.bold));
            document.add(new Paragraph("Seller data is not configured.", fonts.normal));
            document.add(section("Customer and order", fonts.bold));
            metadata(document, fonts, new String[][]{
                    {"Customer", dash(order.getCustomerName())},
                    {"Tracking number", dash(order.getTrackingNumber())},
                    {"Order status", order.getStatus().name()}
            });
            document.add(section("Items", fonts.bold));
            PdfPTable table = table(new float[]{4f, 1f, 1.5f, 1.5f});
            header(table, fonts, "Description", "Quantity", "Unit price", "Total");
            add(table, "COD amount for order " + order.getId(), fonts);
            add(table, "1", fonts);
            add(table, money(order.getExpectedAmount()), fonts);
            add(table, money(order.getExpectedAmount()), fonts);
            document.add(table);
            document.add(section("Totals", fonts.bold));
            metadata(document, fonts, new String[][]{
                    {"Subtotal", money(order.getExpectedAmount())},
                    {"Tax", "— (tax data is not configured)"},
                    {"Total to collect", money(order.getExpectedAmount())}
            });
            document.add(new Paragraph("This document is generated from currently available order data. "
                    + "It is not a legally valid fiscal invoice.", fonts.italic));
            document.close();
            return new ExportedPdf(output.toByteArray(), "order-invoice-" + order.getId() + ".pdf");
        } catch (DocumentException ex) {
            throw new IllegalStateException("Could not generate the order invoice PDF.", ex);
        }
    }

    private void title(Document document, String app, String title, Fonts fonts) throws DocumentException {
        Paragraph appName = new Paragraph(app, fonts.small);
        appName.setAlignment(Element.ALIGN_RIGHT);
        document.add(appName);
        Paragraph heading = new Paragraph(title, fonts.title);
        heading.setSpacingAfter(16);
        document.add(heading);
    }

    private void summary(Document document, ReconciliationBatch batch, Fonts fonts) throws DocumentException {
        document.add(section("Summary", fonts.bold));
        PdfPTable table = table(new float[]{1f, 1f, 1f, 1f, 1f});
        addSummary(table, "Total rows", batch.getTotalRows(), fonts);
        addSummary(table, "Reconciled", batch.getSuccessfulMatches(), fonts);
        addSummary(table, "Mismatches", batch.getMismatches(), fonts);
        addSummary(table, "Not found", batch.getNotFound(), fonts);
        addSummary(table, "Errors", batch.getErrors(), fonts);
        document.add(table);
    }

    private void addSummary(PdfPTable table, String label, int value, Fonts fonts) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(LIGHT);
        cell.setPadding(8);
        cell.addElement(new Paragraph(label, fonts.small));
        cell.addElement(new Paragraph(String.valueOf(value), fonts.bold));
        table.addCell(cell);
    }

    private void metadata(Document document, Fonts fonts, String[][] values) throws DocumentException {
        PdfPTable table = table(new float[]{1.2f, 2.8f});
        for (String[] value : values) {
            table.addCell(cell(value[0], fonts.bold, 1));
            table.addCell(cell(value[1], fonts.normal, 1));
        }
        document.add(table);
        document.add(new Paragraph(" ", fonts.small));
    }

    private Paragraph section(String text, Font font) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setSpacingBefore(10);
        paragraph.setSpacingAfter(6);
        return paragraph;
    }

    private PdfPTable table(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        return table;
    }

    private void header(PdfPTable table, Fonts fonts, String... values) {
        for (String value : values) {
            PdfPCell cell = cell(value, fonts.boldWhite, 1);
            cell.setBackgroundColor(NAVY);
            table.addCell(cell);
        }
    }

    private void add(PdfPTable table, String value, Fonts fonts) {
        table.addCell(cell(value, fonts.normal, 1));
    }

    private PdfPCell cell(String value, Font font, int padding) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setPadding(padding + 2);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private String difference(ReconciliationItem item) {
        if (item.getExpectedAmount() == null || item.getCollectedAmount() == null) return "—";
        return money(item.getCollectedAmount().subtract(item.getExpectedAmount()));
    }

    private String money(BigDecimal value) {
        if (value == null) return "—";
        return NumberFormat.getNumberInstance(Locale.ROOT).format(value.setScale(2));
    }

    private String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Fonts fonts() {
        BaseFont base;
        try {
            base = unicodeBaseFont();
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Could not initialize PDF fonts.", ex);
        }
        return new Fonts(new Font(base, 9), new Font(base, 9, Font.ITALIC),
                new Font(base, 9, Font.BOLD), new Font(base, 9, Font.BOLD, Color.WHITE),
                new Font(base, 8), new Font(base, 18, Font.BOLD, NAVY));
    }

    private BaseFont unicodeBaseFont() throws DocumentException, IOException {
        String configured = System.getenv("PDF_FONT_PATH");
        String[] candidates = {
                configured,
                "C:\\Windows\\Fonts\\arial.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && Files.isRegularFile(Path.of(candidate))) {
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        }
        return BaseFont.createFont("Helvetica", BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    public record ExportedPdf(byte[] content, String fileName) { }

    private record Fonts(Font normal, Font italic, Font bold, Font boldWhite, Font small, Font title) { }

    private static final class FooterEvent extends PdfPageEventHelper {
        private final Font font = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.GRAY);

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Phrase footer = new Phrase("COD Reconciliation System  |  Page " + writer.getPageNumber(), font);
            float y = document.bottomMargin() - 18;
            com.lowagie.text.pdf.ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                    footer, (document.left() + document.right()) / 2, y, 0);
        }
    }
}
