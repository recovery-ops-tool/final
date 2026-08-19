package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.ReplicaRoutingContext;
import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.ExportFormat;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.enums.ReportType;
import com.recoverpro.server.repository.ReportJobRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.ExportService;
import com.recoverpro.server.service.export.ExcelReportBuilder;
import com.recoverpro.server.service.export.PdfReportBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Report export (Excel/PDF generation + file persistence). The actual document-building logic
 * lives in {@link ExcelReportBuilder} and {@link PdfReportBuilder} (SYSTEM-PLAN SP40 -- this class
 * was 666 lines mixing workbook/PDF construction with file I/O).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private final ReportJobRepository reportJobRepository;
    private final AuditService auditService;

    private final ExcelReportBuilder excelReportBuilder = new ExcelReportBuilder();
    private final PdfReportBuilder pdfReportBuilder = new PdfReportBuilder();

    @Value("${app.storage.reports-path:./reports}")
    private String reportsPath;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(IST);

    @Override
    public byte[] generateExcel(Object reportData, String reportTypeName) {
        log.info("Generating Excel — type={}", reportTypeName);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ReportType type = ReportType.valueOf(reportTypeName);
            excelReportBuilder.build(wb, type, reportData);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Excel generation failed: {}", e.getMessage(), e);
            throw new BusinessException("Excel generation failed: " + e.getMessage());
        }
    }

    @Override
    public byte[] generatePdf(Object reportData, String reportTypeName) {
        log.info("Generating PDF — type={}", reportTypeName);
        try {
            ReportType type = ReportType.valueOf(reportTypeName);
            return pdfReportBuilder.build(type, reportData);
        } catch (Exception e) {
            log.error("PDF generation failed: {}", e.getMessage(), e);
            throw new BusinessException("PDF generation failed: " + e.getMessage());
        }
    }

    @Override
    public String saveToFile(byte[] content, String fileName, UUID orgId) {
        try {
            Path dir = Paths.get(reportsPath, String.valueOf(orgId));
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, content);
            return filePath.toString();
        } catch (IOException e) {
            log.error("Failed to save report: {}", e.getMessage(), e);
            throw new BusinessException("Failed to save report file: " + e.getMessage());
        }
    }

    @Override
    public String buildFileName(String reportType, String format, UUID orgId) {
        String ext = ExportFormat.EXCEL.name().equalsIgnoreCase(format) ? "xlsx" : "pdf";
        return reportType.toLowerCase() + "_" + TS_FMT.format(Instant.now()) + "." + ext;
    }

    @Override
    public Resource exportReport(UUID jobId, UUID orgId) {
        ReportJob job = findReportJob(jobId, orgId);
        if (job.getStatus() != ReportStatus.COMPLETED)
            throw new BusinessException("Report not ready. Status: " + job.getStatus());
        if (job.getFilePath() == null)
            throw new BusinessException("Report file missing for job: " + jobId);
        try {
            Path filePath = Paths.get(job.getFilePath());
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable())
                throw new BusinessException("Report file not accessible: " + jobId);
            // TASK 35.4.b: DATA_EXPORTED specifically (not the pre-existing, INFO-severity
            // REPORT_EXPORTED) -- a downloaded report is a bulk PII export, and this action's
            // HIGH severity reflects that correctly. "filters used" is job.getParameters(), the
            // original ReportRequest already serialized onto this row at generation time
            // (ReportingServiceImpl.enqueueReport). "row count" is NOT captured here -- no report
            // builder in this codebase currently records one on the ReportJob row; doing so needs
            // a schema change threaded through every ReportType's builder, out of proportion to
            // this session's pass. Flagged, not solved.
            java.util.Map<String, Object> metadata = new java.util.HashMap<>(java.util.Map.of(
                    "reportType", job.getReportType().name(),
                    "format", job.getExportFormat().name()));
            if (job.getParameters() != null) {
                metadata.put("filters", job.getParameters());
            }
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.DATA_EXPORTED)
                    .resourceType(AuditResourceType.REPORT)
                    .resourceId(jobId.toString())
                    .organizationIdOverride(orgId)
                    .metadata(metadata)
                    .build());
            return resource;
        } catch (MalformedURLException e) {
            throw new BusinessException("Invalid file path for job: " + jobId);
        }
    }

    /**
     * SYSTEM 02 TASK 2.4: routed to the replica -- safe here specifically because this call has
     * no enclosing {@code @Transactional} of its own, so it gets a fresh, independent connection
     * checkout every time (unlike {@code ReportJobExecutor.buildReportData}, deliberately NOT
     * routed -- see {@link ReplicaRoutingContext}'s javadoc for why).
     */
    @Override
    public ReportJob findReportJob(UUID jobId, UUID orgId) {
        return ReplicaRoutingContext.runOnReplica(() ->
                reportJobRepository.findByIdAndOrganizationId(jobId, orgId)
                        .orElseThrow(() -> new ResourceNotFoundException("Report job not found: " + jobId)));
    }
}
