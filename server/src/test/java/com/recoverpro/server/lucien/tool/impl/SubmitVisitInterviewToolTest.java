package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.VisitLogResponse;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.CollectionService;
import com.recoverpro.server.service.NonContactableService;
import com.recoverpro.server.service.PtpService;
import com.recoverpro.server.service.VisitLogService;
import com.recoverpro.server.service.storage.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmitVisitInterviewToolTest {

    @Mock private VisitLogService visitLogService;
    @Mock private PtpService ptpService;
    @Mock private CollectionService collectionService;
    @Mock private NonContactableService nonContactableService;
    @Mock private AllocationService allocationService;
    @Mock private OrgIsolationGuard orgIsolationGuard;
    @Mock private StoragePort storagePort;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SubmitVisitInterviewTool tool;
    private UserPrincipal principal;

    private static final UUID ALLOCATION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        tool = new SubmitVisitInterviewTool(visitLogService, ptpService, collectionService,
                nonContactableService, allocationService, orgIsolationGuard, storagePort, objectMapper);

        principal = mock(UserPrincipal.class);
        lenient().when(principal.getId()).thenReturn(AGENT_ID);
        lenient().when(principal.getOrganizationId()).thenReturn(ORG_ID);
        lenient().when(principal.getUsername()).thenReturn("agent@example.com");

        lenient().when(allocationService.getAllocationById(ALLOCATION_ID)).thenReturn(
                AllocationResponse.builder().id(ALLOCATION_ID).organizationId(ORG_ID)
                        .loanNumber("LN-1").borrowerName("Jane Borrower").build());

        lenient().when(storagePort.readBytes(anyString())).thenReturn(new byte[]{1, 2, 3});

        lenient().when(visitLogService.create(any(), any(), any(), any(), any(), any()))
                .thenReturn(VisitLogResponse.builder().id(UUID.randomUUID()).build());
    }

    @Test
    void name_isStableToolId() {
        assertThat(tool.name()).isEqualTo("submit_visit_interview");
        assertThat(tool.isWriteOperation()).isTrue();
    }

    @Test
    void execute_paidWithoutAmount_throwsBusinessException() {
        ObjectNode args = baseArgs("PAID");

        assertThatThrownBy(() -> tool.execute(args, principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Amount collected");
    }

    @Test
    void execute_ptpWithoutPromisedDate_throwsBusinessException() {
        ObjectNode args = baseArgs("PTP");
        args.put("promisedAmount", 5000);

        assertThatThrownBy(() -> tool.execute(args, principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Promised date");
    }

    @Test
    void execute_followUpWithoutReason_throwsBusinessException() {
        ObjectNode args = baseArgs("FOLLOW_UP");
        args.put("nextVisitDate", "2026-08-05");

        assertThatThrownBy(() -> tool.execute(args, principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void execute_missingImage1Key_throwsBusinessException() {
        ObjectNode args = baseArgs("RTP");
        // No image1Key set — loadImage() returns null, tool should refuse before writing anything.
        assertThatThrownBy(() -> tool.execute(args, principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("selfie");

        verifyNoInteractions(visitLogService);
    }

    @Test
    void execute_ptpValid_createsVisitLogAndPtp() {
        ObjectNode args = baseArgs("PTP");
        args.put("promisedAmount", 5000);
        args.put("promisedDate", "2026-08-10");
        args.put("image1Key", "staged/key1");

        String result = tool.execute(args, principal);

        assertThat(result).doesNotContain("\"error\"");
        verify(visitLogService).create(any(), any(MultipartFile.class), isNull(), isNull(), eq(AGENT_ID), eq(AGENT_ID));
        verify(ptpService).createPtp(argThat(req ->
                req.getAllocationId().equals(ALLOCATION_ID)
                        && req.getBorrowerName().equals("Jane Borrower")
                        && req.getPromisedAmount().intValue() == 5000), eq(AGENT_ID));
        verifyNoInteractions(collectionService, nonContactableService);
    }

    @Test
    void execute_paidValid_createsVisitLogAndCollection() {
        ObjectNode args = baseArgs("PAID");
        args.put("amountCollected", 2500);
        args.put("paymentMode", "CASH");
        args.put("image1Key", "staged/key1");

        String result = tool.execute(args, principal);

        assertThat(result).doesNotContain("\"error\"");
        verify(collectionService).submit(argThat(req ->
                req.getAllocationId().equals(ALLOCATION_ID)
                        && req.getAmount().intValue() == 2500
                        && req.isCashHandlingAcknowledged()), eq(AGENT_ID));
        verifyNoInteractions(ptpService, nonContactableService);
    }

    @Test
    void execute_rtpValid_createsNonContactable() {
        ObjectNode args = baseArgs("RTP");
        args.put("image1Key", "staged/key1");

        String result = tool.execute(args, principal);

        assertThat(result).doesNotContain("\"error\"");
        verify(nonContactableService).create(argThat(req ->
                req.getAllocationId().equals(ALLOCATION_ID) && "REFUSED".equals(req.getReason())),
                eq(AGENT_ID), eq(ORG_ID));
        verifyNoInteractions(ptpService, collectionService);
    }

    @Test
    void execute_downstreamFailure_doesNotFailTheVisitSubmission() {
        ObjectNode args = baseArgs("PAID");
        args.put("amountCollected", 2500);
        args.put("paymentMode", "CASH");
        args.put("image1Key", "staged/key1");
        when(collectionService.submit(any(), any())).thenThrow(new RuntimeException("downstream boom"));

        String result = tool.execute(args, principal);

        assertThat(result).doesNotContain("\"error\"");
        verify(visitLogService).create(any(), any(), any(), any(), any(), any());
    }

    private ObjectNode baseArgs(String disp) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("allocationId", ALLOCATION_ID.toString());
        node.put("contactability", "CONTACTED_AT_RESIDENCE");
        node.put("disp", disp);
        node.put("contactPerson", "Jane Borrower");
        node.put("contactNumber", "9999999999");
        node.put("visitNotes", "Spoke with the borrower at the door.");
        node.put("latitude", 12.9716);
        node.put("longitude", 77.5946);
        return node;
    }
}
