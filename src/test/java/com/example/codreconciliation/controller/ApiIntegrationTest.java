package com.example.codreconciliation.controller;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.codreconciliation.repository.ReconciliationBatchRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import com.lowagie.text.pdf.PdfReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconciliationBatchRepository batches;

    @Test
    void protectedApisRejectAnonymousUsersButHealthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/reconcile/upload"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders").with(httpBasic("test-admin", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/csrf").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isString());
    }

    @Test
    void ordersCanBeListedFilteredAndLoaded() throws Exception {
        mockMvc.perform(get("/api/orders").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));

        mockMvc.perform(get("/api/orders").param("search", "TRK-1005")
                        .with(httpBasic("test-admin", "test-password"))
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].trackingNumber", is("TRK-1005")));

        mockMvc.perform(get("/api/orders/999999").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.path", is("/api/orders/999999")));
    }

    @Test
    void createsOrdersWithPendingStatusAndRejectsInvalidOrDuplicateRequests() throws Exception {
        String request = """
                {"trackingNumber":"TRK-NEW-1","customerName":"New Customer","expectedAmount":42.50}
                """;
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trackingNumber", is("TRK-NEW-1")))
                .andExpect(jsonPath("$.status", is("PENDING")));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("An order with tracking number 'TRK-NEW-1' already exists.")));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNumber\":\" \",\"customerName\":\"\",\"expectedAmount\":0}")
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void mutatingOrderRequestsRequireCsrf() throws Exception {
        String request = """
                {"trackingNumber":"TRK-CSRF-1","customerName":"CSRF Customer","expectedAmount":10.00}
                """;
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/orders/5").contentType(MediaType.APPLICATION_JSON).content(request)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatesOrdersAndRejectsUnknownOrder() throws Exception {
        String request = """
                {"trackingNumber":"TRK-1005","customerName":"Updated Customer","expectedAmount":99.99}
                """;
        mockMvc.perform(put("/api/orders/5").contentType(MediaType.APPLICATION_JSON).content(request)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName", is("Updated Customer")))
                .andExpect(jsonPath("$.expectedAmount", is(99.99)));

        mockMvc.perform(put("/api/orders/999999").contentType(MediaType.APPLICATION_JSON).content(request)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewsAndConfirmsOrderImportWithoutAcceptingStatuses() throws Exception {
        MockMultipartFile file = orderImportXlsx(
                new Object[]{"IMP-1001", "Imported One", 12.50, "RECONCILED"},
                new Object[]{"IMP-1002", "Imported Two", 25.00, "AMOUNT_MISMATCH"},
                new Object[]{"IMP-1001", "Duplicate", 30.00, "PENDING"});
        var preview = mockMvc.perform(multipart("/api/orders/import/preview").file(file)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows", is(3)))
                .andExpect(jsonPath("$.validRows", is(2)))
                .andExpect(jsonPath("$.duplicateRows", is(1)))
                .andExpect(jsonPath("$.errors[0].reason", containsString("Duplicate")))
                .andReturn();
        String importId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(preview.getResponse().getContentAsString()).get("importId").asText();
        mockMvc.perform(post("/api/orders/import/confirm").param("importId", importId)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount", is(2)));
        mockMvc.perform(get("/api/orders").param("search", "IMP-1001")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status", is("PENDING")));
    }

    @Test
    void orderImportRequiresAuthenticationAndCsrf() throws Exception {
        MockMultipartFile file = orderImportXlsx(new Object[]{"IMP-CSRF", "Customer", 10.00, "PENDING"});
        mockMvc.perform(multipart("/api/orders/import/preview").file(file)
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadHistoryAndDashboardExposePersistedResults() throws Exception {
        MockMultipartFile file = xlsx(
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)},
                new Object[]{"UNKNOWN-API", 4.25, LocalDate.of(2026, 10, 20)});

        mockMvc.perform(multipart("/api/reconcile/upload").file(file)
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows", is(2)))
                .andExpect(jsonPath("$.successfulMatches", is(1)))
                .andExpect(jsonPath("$.notFound", is(1)))
                .andExpect(jsonPath("$.items", hasSize(2)));

        long id = batches.findAllByOrderByUploadedAtDesc().get(0).getId();
        var export = mockMvc.perform(get("/api/reconcile/history/" + id + "/export")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        containsString("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(
                export.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheet("Summary").getRow(2).getCell(1).getNumericCellValue())
                    .isEqualTo((double) id);
            assertThat(workbook.getSheet("Summary").getRow(5).getCell(1).getNumericCellValue()).isEqualTo(2.0);
            assertThat(workbook.getSheet("Summary").getRow(6).getCell(1).getNumericCellValue()).isEqualTo(1.0);
            assertThat(workbook.getSheet("Summary").getRow(8).getCell(1).getNumericCellValue()).isEqualTo(1.0);
            assertThat(workbook.getSheet("Results").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("Results").getRow(1).getCell(5).getStringCellValue())
                    .isEqualTo("RECONCILED");
            assertThat(workbook.getSheet("Results").getRow(2).getCell(2).getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
        }

        mockMvc.perform(get("/api/reconcile/history").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchId", is((int) id)));
        mockMvc.perform(get("/api/reconcile/history").param("search", "API-REPORT")
                        .param("outcome", "HAS_MATCHES").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/reconcile/history").param("search", "report")
                        .param("outcome", "HAS_NOT_FOUND")
                        .param("from", LocalDate.now(ZoneOffset.UTC).toString())
                        .param("to", LocalDate.now(ZoneOffset.UTC).toString())
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/reconcile/history").param("outcome", "PROBLEMS")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].notFound", is(1)));
        MockMultipartFile issueReport = xlsx(
                new Object[]{"TRK-1002", 1.00, LocalDate.of(2026, 10, 20)},
                new Object[]{"TRK-1002", 2.00, LocalDate.of(2026, 10, 20)});
        mockMvc.perform(multipart("/api/reconcile/upload").file(issueReport)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/reconcile/history").param("outcome", "HAS_MISMATCHES")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].mismatches", is(1)));
        mockMvc.perform(get("/api/reconcile/history").param("outcome", "HAS_ERRORS")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].errors", is(1)));
        mockMvc.perform(get("/api/reconcile/history").param("from", "2026-10-31")
                        .param("to", "2026-10-01").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status", is(400)));
        mockMvc.perform(get("/api/reconcile/history").param("outcome", "UNKNOWN")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status", is(400)));
        mockMvc.perform(get("/api/reconcile/history/" + id).with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(get("/api/reconcile/history/999999").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reconcile/history/999999/export")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/dashboard/stats").with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBatches").isNumber())
                .andExpect(jsonPath("$.historicalNotFound").isNumber());
    }

    @Test
    void rejectsIdenticalReportContentButAcceptsSameFilenameWithDifferentContent() throws Exception {
        MockMultipartFile first = xlsx(
                new Object[]{"UNKNOWN-HASH-1", 4.25, LocalDate.of(2026, 10, 20)});
        byte[] identicalContent = first.getBytes();

        mockMvc.perform(multipart("/api/reconcile/upload")
                        .file(new MockMultipartFile("file", "same-name.xlsx",
                                first.getContentType(), identicalContent))
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/reconcile/upload")
                        .file(new MockMultipartFile("file", "renamed.xlsx",
                                first.getContentType(), identicalContent))
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message",
                        is("This reconciliation report has already been processed.")));

        mockMvc.perform(multipart("/api/reconcile/upload")
                        .file(new MockMultipartFile("file", "same-name.xlsx",
                                first.getContentType(), xlsx(
                                        new Object[]{"UNKNOWN-HASH-2", 4.25,
                                                LocalDate.of(2026, 10, 20)}).getBytes()))
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidUploadsReturnConsistentErrors() throws Exception {
        mockMvc.perform(multipart("/api/reconcile/upload")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isForbidden());

        mockMvc.perform(multipart("/api/reconcile/upload")
                        .with(httpBasic("test-admin", "test-password"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("The multipart field 'file' is required.")));

        mockMvc.perform(get("/api/orders").with(httpBasic("test-admin", "test-password"))
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void exportRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/reconcile/history/1/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pdfExportsAreAuthenticatedAndContainPersistedData() throws Exception {
        MockMultipartFile source = xlsx(
                new Object[]{"TRK-1001", 149.90, LocalDate.of(2026, 10, 20)},
                new Object[]{"UNKNOWN-PDF", 4.25, LocalDate.of(2026, 10, 20)});
        MockMultipartFile file = new MockMultipartFile("file", "pdf-export.xlsx",
                source.getContentType(), source.getBytes());
        var upload = mockMvc.perform(multipart("/api/reconcile/upload").file(file)
                        .with(httpBasic("test-admin", "test-password")).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        long batchId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(upload.getResponse().getContentAsString()).get("batchId").asLong();

        var report = mockMvc.perform(get("/api/reconcile/history/" + batchId + "/export/pdf")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
        try (PdfReader reader = new PdfReader(report.getResponse().getContentAsByteArray())) {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            assertThat(new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1))
                    .contains("COD Reconciliation Report", "TRK-1001", "UNKNOWN-PDF");
        }
        mockMvc.perform(get("/api/reconcile/history/999999/export/pdf")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/orders/1/invoice/pdf")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("order-invoice-1.pdf")));
        mockMvc.perform(get("/api/reconcile/history/" + batchId + "/export/pdf"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/999999/invoice/pdf")
                        .with(httpBasic("test-admin", "test-password")))
                .andExpect(status().isNotFound());
    }

    private MockMultipartFile xlsx(Object[]... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Tracking Number");
            sheet.getRow(0).createCell(1).setCellValue("Collected Amount");
            sheet.getRow(0).createCell(2).setCellValue("Delivery Date");
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue((String) rows[i][0]);
                row.createCell(1).setCellValue((Double) rows[i][1]);
                row.createCell(2).setCellValue((LocalDate) rows[i][2]);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
                row.getCell(2).setCellStyle(style);
            }

            workbook.write(output);
            return new MockMultipartFile("file", "api-report.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private MockMultipartFile orderImportXlsx(Object[]... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Tracking Number");
            sheet.getRow(0).createCell(1).setCellValue("Customer Name");
            sheet.getRow(0).createCell(2).setCellValue("Expected Amount");
            sheet.getRow(0).createCell(3).setCellValue("Status");
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue((String) rows[i][0]);
                row.createCell(1).setCellValue((String) rows[i][1]);
                row.createCell(2).setCellValue((Double) rows[i][2]);
                row.createCell(3).setCellValue((String) rows[i][3]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "orders.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
