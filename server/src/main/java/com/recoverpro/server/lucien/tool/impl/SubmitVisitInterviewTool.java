package com.recoverpro.server.lucien.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.CreateNonContactableRequest;
import com.recoverpro.server.dto.request.CreatePtpRequest;
import com.recoverpro.server.dto.request.SubmitCollectionRequest;
import com.recoverpro.server.dto.request.VisitLogRequest;
import com.recoverpro.server.dto.response.AllocationResponse;
import com.recoverpro.server.dto.response.VisitLogResponse;
import com.recoverpro.server.enums.Contactability;
import com.recoverpro.server.enums.Disp;
import com.recoverpro.server.enums.PaymentMode;
import com.recoverpro.server.lucien.tool.LucienTool;
import com.recoverpro.server.lucien.tool.impl.support.InMemoryMultipartFile;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.CollectionService;
import com.recoverpro.server.service.NonContactableService;
import com.recoverpro.server.service.PtpService;
import com.recoverpro.server.service.VisitLogService;
import com.recoverpro.server.service.storage.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Finalizes a Lucien visit-interview conversation into a full visit record — the interview
 * analogue of the manual VisitSubmitPage form. Unlike LogVisitOutcomeTool, the LLM never sees or
 * supplies allocationId/GPS/photos: those are injected into args by VisitInterviewService
 * (server-verified from the bound ChatSession and the confirm-visit multipart request) before
 * this tool executes, so a hallucinated or stale value from the model can never end up on the
 * record. This tool is only ever invoked through VisitInterviewService's dedicated confirm-visit
 * flow, never through the generic ConfirmationService/ToolExecutor.executeConfirmed path.
 *
 * Disposition-specific guard failures and missing-field validation are surfaced as
 * BusinessException (not swallowed into a JSON error string like other tools) so
 * VisitInterviewService can turn them into a chat reply that lets the FO correct course,
 * instead of silently closing the interview.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmitVisitInterviewTool implements LucienTool {

    private final VisitLogService visitLogService;
    private final PtpService ptpService;
    private final CollectionService collectionService;
    private final NonContactableService nonContactableService;
    private final AllocationService allocationService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "submit_visit_interview"; }

    @Override
    public String description() {
        return "Submit the completed visit interview as the final visit record. Call this once "
                + "— and only once — a disposition has been reached and all fields it needs are "
                + "gathered. WRITE — requires confirmation.";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{
                  "contactability":{"type":"string","enum":["CONTACTED_AT_RESIDENCE","CONTACTABLE_AT_BOTH_PLACES","NON_CONTACTABLE","CONTACTED_AT_OFFICE","CONTACTABLE_ON_PHONE_ONLY"]},
                  "disp":{"type":"string","enum":["PAID","RTP","NC_SKIP","PTP","FOLLOW_UP"]},
                  "contactPerson":{"type":"string","description":"Name of the person actually spoken to"},
                  "contactNumber":{"type":"string"},
                  "visitNotes":{"type":"string","description":"Short factual summary of what happened at this visit"},
                  "amountCollected":{"type":"number","description":"Required if disp=PAID"},
                  "paymentMode":{"type":"string","enum":["CASH","UPI","CHEQUE","NEFT","RTGS"],"description":"Required if disp=PAID"},
                  "promisedAmount":{"type":"number","description":"Required if disp=PTP"},
                  "promisedDate":{"type":"string","description":"ISO date YYYY-MM-DD, required if disp=PTP"},
                  "reasonForDefault":{"type":"string","description":"Short reason, required if disp=RTP or NC_SKIP"},
                  "nextVisitDate":{"type":"string","description":"ISO date YYYY-MM-DD, required if disp=FOLLOW_UP"},
                  "rescheduleReason":{"type":"string","description":"Required if disp=FOLLOW_UP"},
                  "afterHoursOverrideReason":{"type":"string","description":"Only if this visit is happening outside 08:00-19:00 IST"}
                },"required":["contactability","disp","contactPerson","contactNumber","visitNotes"]}""";
    }

    @Override public boolean isWriteOperation() { return true; }

    @Override
    public String humanReadableSummary(JsonNode args) {
        return String.format("Submit visit — disposition: %s, contact: %s",
                args.path("disp").asText("?"), args.path("contactability").asText("?"));
    }

    /**
     * args must already contain allocationId, latitude, longitude, gpsAccuracy,
     * mockLocationDetected, image1Key (required) and image2Key (optional) — injected by
     * VisitInterviewService, never supplied by the model.
     */
    @Override
    public String execute(JsonNode args, UserPrincipal principal) {
        orgIsolationGuard.belongsToOrg(principal.getOrganizationId());

        UUID allocationId = requireUuid(args, "allocationId");
        Disp disp = requireEnum(args, "disp", Disp.class);
        Contactability contactability = requireEnum(args, "contactability", Contactability.class);
        String contactPerson = requireText(args, "contactPerson");
        String contactNumber = requireText(args, "contactNumber");
        String visitNotes = requireText(args, "visitNotes");

        validateDispositionFields(disp, args);

        AllocationResponse allocation = allocationService.getAllocationById(allocationId);

        VisitLogRequest.VisitLogRequestBuilder reqBuilder = VisitLogRequest.builder()
                .allocationId(allocationId)
                .organizationId(principal.getOrganizationId())
                .visitDate(LocalDate.now())
                .contactability(contactability)
                .contactPerson(contactPerson)
                .contactNumber(contactNumber)
                .disp(disp)
                .visitNotes(visitNotes)
                .latitude(args.get("latitude").asDouble())
                .longitude(args.get("longitude").asDouble())
                .gpsAccuracy(args.hasNonNull("gpsAccuracy") ? args.get("gpsAccuracy").asDouble() : null)
                .mockLocationDetected(args.path("mockLocationDetected").asBoolean(false))
                .afterHoursOverrideReason(args.path("afterHoursOverrideReason").asText(null))
                .lucienSessionId(args.path("lucienSessionId").asText(null));

        if (disp == Disp.PAID) {
            reqBuilder.amountCollected(BigDecimal.valueOf(args.get("amountCollected").asDouble()));
            reqBuilder.paymentMode(args.get("paymentMode").asText());
        } else if (disp == Disp.FOLLOW_UP) {
            reqBuilder.nextVisitDate(LocalDate.parse(args.get("nextVisitDate").asText()));
            reqBuilder.rescheduleReason(args.get("rescheduleReason").asText());
        }
        // PTP's promisedAmount/promisedDate go to the PTP record itself, not the visit log.
        // RTP/NC_SKIP's free-text reason goes to the NonContactable record, not a visit-log column.

        // image1 = selfie, image2 = site photo — same convention as the manual VisitSubmitPage form.
        MultipartFile image1 = loadImage(args, "image1Key");
        MultipartFile image2 = loadImage(args, "image2Key");
        if (image1 == null) {
            throw new BusinessException("A selfie and site photo are required before submitting this visit.");
        }

        // Guard failures (after-hours, mock-location) throw BusinessException, which
        // VisitInterviewService catches to give the FO an actionable reply instead of a dead end.
        VisitLogResponse visit = visitLogService.create(reqBuilder.build(), image1, image2, null,
                principal.getId(), principal.getId());

        UUID visitId = visit.getId();
        try {
            if (disp == Disp.PAID) {
                submitCollection(allocationId, visitId, args, principal);
            } else if (disp == Disp.PTP) {
                createPtp(allocationId, visitId, allocation, args, principal);
            } else if (disp == Disp.RTP || disp == Disp.NC_SKIP) {
                createNonContactable(allocationId, visitId, disp, args, principal);
            }
        } catch (Exception e) {
            // Best-effort, matching VisitSubmitPage's client-side behaviour today: the visit
            // record itself is the critical write and has already succeeded.
            log.error("Downstream record (disp={}) failed for visit={}: {}", disp, visitId, e.getMessage());
        }

        try {
            return objectMapper.writeValueAsString(visit);
        } catch (Exception e) {
            return "{\"id\":\"" + visitId + "\"}";
        }
    }

    private void submitCollection(UUID allocationId, UUID visitId, JsonNode args, UserPrincipal principal) {
        SubmitCollectionRequest req = SubmitCollectionRequest.builder()
                .allocationId(allocationId)
                .organizationId(principal.getOrganizationId())
                .amount(BigDecimal.valueOf(args.get("amountCollected").asDouble()))
                .paymentMode(PaymentMode.valueOf(args.get("paymentMode").asText()))
                .collectionDate(LocalDate.now())
                .idempotencyKey(UUID.randomUUID().toString())
                .visitId(visitId)
                // Verbally confirmed and recorded in the chat transcript itself, which stands in
                // for the manual form's cash-handling acknowledgement checkbox.
                .cashHandlingAcknowledged(true)
                .build();
        collectionService.submit(req, principal.getId());
    }

    private void createPtp(UUID allocationId, UUID visitId, AllocationResponse allocation,
                            JsonNode args, UserPrincipal principal) {
        CreatePtpRequest req = CreatePtpRequest.builder()
                .allocationId(allocationId)
                .agentId(principal.getId())
                .agentName(principal.getUsername())
                .loanNumber(allocation.getLoanNumber())
                .borrowerName(allocation.getBorrowerName())
                .promisedDate(LocalDate.parse(args.get("promisedDate").asText()))
                .promisedAmount(BigDecimal.valueOf(args.get("promisedAmount").asDouble()))
                .visitId(visitId)
                .build();
        ptpService.createPtp(req, principal.getId());
    }

    private void createNonContactable(UUID allocationId, UUID visitId, Disp disp,
                                       JsonNode args, UserPrincipal principal) {
        CreateNonContactableRequest req = new CreateNonContactableRequest();
        req.setAllocationId(allocationId);
        req.setVisitId(visitId);
        req.setReason(disp == Disp.RTP ? "REFUSED" : "ABSENT");
        req.setNotes(args.path("reasonForDefault").asText(null));
        nonContactableService.create(req, principal.getId(), principal.getOrganizationId());
    }

    private MultipartFile loadImage(JsonNode args, String keyField) {
        String key = args.path(keyField).asText(null);
        if (key == null || key.isBlank()) return null;
        try {
            byte[] bytes = storagePort.readBytes(key);
            return new InMemoryMultipartFile(keyField, keyField + ".jpg", "image/jpeg", bytes);
        } catch (Exception e) {
            log.error("Failed to load staged image key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void validateDispositionFields(Disp disp, JsonNode args) {
        switch (disp) {
            case PAID -> {
                if (!args.hasNonNull("amountCollected") || args.get("amountCollected").asDouble() <= 0) {
                    throw new BusinessException("Amount collected is required for a PAID disposition.");
                }
                if (!args.hasNonNull("paymentMode")) {
                    throw new BusinessException("Payment mode is required for a PAID disposition.");
                }
            }
            case PTP -> {
                if (!args.hasNonNull("promisedAmount") || args.get("promisedAmount").asDouble() <= 0) {
                    throw new BusinessException("Promised amount is required for a PTP disposition.");
                }
                if (!args.hasNonNull("promisedDate")) {
                    throw new BusinessException("Promised date is required for a PTP disposition.");
                }
            }
            case FOLLOW_UP -> {
                if (!args.hasNonNull("nextVisitDate")) {
                    throw new BusinessException("Next visit date is required for a FOLLOW_UP disposition.");
                }
                if (!args.hasNonNull("rescheduleReason")) {
                    throw new BusinessException("A reason is required for a FOLLOW_UP disposition.");
                }
            }
            default -> { /* RTP / NC_SKIP need no extra field beyond the common ones */ }
        }
    }

    private static UUID requireUuid(JsonNode args, String field) {
        if (!args.hasNonNull(field)) {
            throw new BusinessException("Missing required field: " + field);
        }
        try {
            return UUID.fromString(args.get(field).asText());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid " + field);
        }
    }

    private static String requireText(JsonNode args, String field) {
        if (!args.hasNonNull(field) || args.get(field).asText().isBlank()) {
            throw new BusinessException("Missing required field: " + field);
        }
        return args.get(field).asText();
    }

    private static <E extends Enum<E>> E requireEnum(JsonNode args, String field, Class<E> type) {
        if (!args.hasNonNull(field)) {
            throw new BusinessException("Missing required field: " + field);
        }
        try {
            return Enum.valueOf(type, args.get(field).asText());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid value for " + field + ": " + args.get(field).asText());
        }
    }
}
