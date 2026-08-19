package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.LocationPingRequest;
import com.recoverpro.server.dto.request.SosRequest;
import com.recoverpro.server.dto.request.LocationPingRequest;
import com.recoverpro.server.dto.request.SosRequest;
import com.recoverpro.server.dto.request.StartShiftRequest;
import com.recoverpro.server.dto.response.AgentLiveStatusResponse;
import com.recoverpro.server.dto.response.AgentShiftResponse;
import com.recoverpro.server.dto.response.IncidentReportResponse;
import com.recoverpro.server.entity.AgentLocationPing;
import com.recoverpro.server.entity.AgentShift;
import com.recoverpro.server.entity.IncidentReport;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.enums.NotificationType;
import com.recoverpro.server.enums.ShiftStatus;
import com.recoverpro.server.repository.AgentLocationPingRepository;
import com.recoverpro.server.repository.AgentShiftRepository;
import com.recoverpro.server.repository.IncidentReportRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AgentFieldService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.storage.StoragePort;
import com.recoverpro.server.websocket.LiveTrackWebSocketHandler;
import com.recoverpro.server.websocket.SosAudioWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AgentFieldServiceImpl implements AgentFieldService {

    private final AgentShiftRepository shiftRepository;
    private final AgentLocationPingRepository pingRepository;
    private final IncidentReportRepository incidentRepository;
    private final UserRepository userRepository;
    private final OrgIsolationGuard orgIsolationGuard;
    private final StoragePort storagePort;
    private final NotificationService notificationService;

    @Autowired
    private LiveTrackWebSocketHandler liveTrackWebSocketHandler;

    @Autowired
    private SosAudioWebSocketHandler sosAudioWebSocketHandler;

    @Value("${app.storage.sos-audio-path:./uploads/sos-audio}")
    private String sosAudioPath;

    private static final int PING_RATE_CAP_PER_MINUTE = 12;
    private static final int STALE_PING_THRESHOLD_SECONDS = 60;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(java.time.ZoneId.of("Asia/Kolkata"));

    @Override
    public AgentShiftResponse startShift(StartShiftRequest request) {
        UserPrincipal principal = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        UUID agentId = principal.getId();
        UUID orgId   = principal.getOrganizationId();

        AgentShift existing = shiftRepository
                .findTopByAgentIdAndStatusOrderByStartedAtDesc(agentId, ShiftStatus.ACTIVE)
                .orElse(null);
        if (existing != null) return toResponse(existing);
        AgentShift shift = AgentShift.builder()
                .agentId(agentId)
                .organizationId(orgId)
                .startedAt(Instant.now())
                .status(ShiftStatus.ACTIVE)
                .build();
        log.info("Shift started: agent={}", agentId);
        return toResponse(shiftRepository.save(shift));
    }

    @Override
    public AgentShiftResponse endShift(UUID agentId) {
        AgentShift active = shiftRepository
                .findTopByAgentIdAndStatusOrderByStartedAtDesc(agentId, ShiftStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException("No active shift to end for agent " + agentId));
        active.setStatus(ShiftStatus.ENDED);
        active.setEndedAt(Instant.now());
        log.info("Shift ended: agent={}, shift={}", agentId, active.getId());
        return toResponse(shiftRepository.save(active));
    }

    @Override
    @Transactional(readOnly = true)
    public AgentShiftResponse currentShift(UUID agentId, UUID callerId) {
        AgentShift active = shiftRepository
                .findTopByAgentIdAndStatusOrderByStartedAtDesc(agentId, ShiftStatus.ACTIVE)
                .orElse(null);
        if (active == null) return null;
        if (!agentId.equals(callerId) && !(isSupervisor() && orgIsolationGuard.belongsToOrg(active.getOrganizationId()))) {
            throw new ResourceNotFoundException("Shift not found for agent " + agentId);
        }
        return toResponse(active);
    }

    @Override
    public void recordPing(LocationPingRequest request, UUID agentId) {
        if (request.getRecordedAt() != null) {
            long ageSecs = Instant.now().getEpochSecond() - request.getRecordedAt().getEpochSecond();
            if (ageSecs > STALE_PING_THRESHOLD_SECONDS || ageSecs < -10) {
                throw new BusinessException(
                        "Location ping rejected: timestamp is " + ageSecs
                                + "s old (max " + STALE_PING_THRESHOLD_SECONDS + "s).");
            }
        }

        Instant oneMinuteAgo = Instant.now().minusSeconds(60);
        long recentCount = pingRepository.countByAgentIdAndRecordedAtAfter(
                agentId, oneMinuteAgo);
        if (recentCount >= PING_RATE_CAP_PER_MINUTE) {
            log.warn("Ping rate-cap exceeded: agent={} count={}", agentId, recentCount);
            throw new BusinessException(
                    "Location ping rate limit exceeded: max " + PING_RATE_CAP_PER_MINUTE + " pings/min.");
        }

        AgentShift active = shiftRepository
                .findTopByAgentIdAndStatusOrderByStartedAtDesc(agentId, ShiftStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Cannot ping: no active shift for agent " + agentId));

        Instant pingTime = request.getRecordedAt() != null ? request.getRecordedAt() : Instant.now();

        AgentLocationPing ping = AgentLocationPing.builder()
                .agentId(agentId)
                .shiftId(active.getId())
                .lat(request.getLat())
                .lng(request.getLng())
                .accuracy(request.getAccuracy())
                .batteryLevel(request.getBatteryLevel())
                .mockLocationDetected(request.isMockLocationDetected())
                .recordedAt(pingTime)
                .build();
        pingRepository.save(ping);

        try {
            userRepository.findById(agentId).ifPresent(u -> {
                String agentName = (u.getFirstName() + " "
                        + (u.getLastName() != null ? u.getLastName() : "")).trim();
                liveTrackWebSocketHandler.relayAgentLocation(
                        agentId, u.getOrganizationId(),
                        request.getLat(), request.getLng(),
                        request.getAccuracy() != null ? request.getAccuracy() : 0.0,
                        agentName);
            });
        } catch (Exception e) {
            // SYSTEM 13 TASK 13.2: was silent -- non-fatal (the ping itself is already persisted
            // above; this is just the live-map relay), but inconsistent with every other broadcast
            // failure in this codebase (LiveTrackWebSocketHandler/SosAudioWebSocketHandler both log
            // at WARN for the exact same kind of failure).
            log.warn("Live-track relay failed for agentId={}: {}", agentId, e.getMessage());
        }
    }

    @Override
    public IncidentReportResponse triggerSos(SosRequest request, UUID agentId) {
        AgentShift active = shiftRepository
                .findTopByAgentIdAndStatusOrderByStartedAtDesc(agentId, ShiftStatus.ACTIVE)
                .orElse(null);

        UUID orgId = active != null ? active.getOrganizationId() : null;
        if (orgId == null) {
            orgId = shiftRepository.findByAgentIdOrderByStartedAtDesc(agentId)
                    .stream().findFirst().map(AgentShift::getOrganizationId).orElse(null);
        }
        if (orgId == null) {
            throw new ResourceNotFoundException(
                    "No shift history found for agent " + agentId + " -- cannot route SOS");
        }

        List<AgentLocationPing> recent =
                pingRepository.findTop20ByAgentIdOrderByRecordedAtDesc(agentId);
        List<Map<String, Object>> snapshot = recent.stream()
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("lat", p.getLat());
                    m.put("lng", p.getLng());
                    m.put("accuracy", p.getAccuracy());
                    m.put("recorded_at", p.getRecordedAt() == null ? null : p.getRecordedAt().toString());
                    m.put("mock", p.isMockLocationDetected());
                    return m;
                })
                .toList();

        Double lat = request.getLat() != null
                ? request.getLat()
                : recent.stream().findFirst().map(AgentLocationPing::getLat).orElse(null);
        Double lng = request.getLng() != null
                ? request.getLng()
                : recent.stream().findFirst().map(AgentLocationPing::getLng).orElse(null);
        Double acc = request.getAccuracy() != null
                ? request.getAccuracy()
                : recent.stream().findFirst().map(AgentLocationPing::getAccuracy).orElse(null);

        IncidentReport incident = IncidentReport.builder()
                .agentId(agentId)
                .organizationId(orgId)
                .shiftId(active == null ? null : active.getId())
                .triggeredAt(Instant.now())
                .lastKnownLat(lat)
                .lastKnownLng(lng)
                .lastKnownAccuracy(acc)
                .recentPings(snapshot)
                .notes(request.getNotes())
                .build();

        IncidentReport saved = incidentRepository.save(incident);
        log.warn("SOS triggered: agent={}, incident={}, lastKnown=({},{})",
                agentId, saved.getId(), lat, lng);

        String agentName = userRepository.findById(agentId).map(User::getFirstName).orElse("A field officer");
        String title = "SOS: " + agentName + " needs help";
        String body = "Field officer " + agentName + " triggered an SOS during their shift. Last known location: ("
                + lat + ", " + lng + "). Respond immediately.";
        notificationService.createForOrgRole(orgId, PlatformConstants.ROLE_TL, NotificationType.TL_TEAM_SOS, title, body);
        notificationService.createForOrgRole(orgId, PlatformConstants.ROLE_ORG_ADMIN, NotificationType.ORG_SOS_TRIGGERED, title, body);

        return toIncidentResponse(saved);
    }

    @Override
    public IncidentReportResponse resolveIncident(UUID id, UUID actingUserId, String notes) {
        IncidentReport incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
        if (!orgIsolationGuard.belongsToOrg(incident.getOrganizationId())) {
            throw new ResourceNotFoundException("Incident not found: " + id);
        }
        if (incident.getResolvedAt() != null) return toIncidentResponse(incident);
        incident.setResolvedAt(Instant.now());
        incident.setResolvedByUserId(actingUserId);
        incident.setResolutionNotes(notes);
        IncidentReportResponse response = toIncidentResponse(incidentRepository.save(incident));
        sosAudioWebSocketHandler.relayEnd(incident.getId());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentLiveStatusResponse> listActiveAgents(UUID organizationId) {
        if (!orgIsolationGuard.belongsToOrg(organizationId)) {
            throw new BusinessException("Access denied: organization mismatch");
        }
        List<AgentShift> activeShifts = shiftRepository
                .findByOrganizationIdAndStatus(organizationId, ShiftStatus.ACTIVE);
        if (activeShifts.isEmpty()) return List.of();

        List<UUID> agentIds = activeShifts.stream().map(AgentShift::getAgentId).distinct().toList();
        Map<UUID, AgentLocationPing> latestByAgent = pingRepository.findLatestPerAgent(agentIds).stream()
                .collect(Collectors.toMap(AgentLocationPing::getAgentId, p -> p));

        return activeShifts.stream()
                .map(s -> {
                    AgentLocationPing latest = latestByAgent.get(s.getAgentId());
                    return AgentLiveStatusResponse.builder()
                            .shiftId(s.getId())
                            .agentId(s.getAgentId())
                            .shiftStartedAt(s.getStartedAt())
                            .lastLat(latest == null ? null : latest.getLat())
                            .lastLng(latest == null ? null : latest.getLng())
                            .lastAccuracy(latest == null ? null : latest.getAccuracy())
                            .lastPingAt(latest == null ? null : latest.getRecordedAt())
                            .lastMockDetected(latest != null && latest.isMockLocationDetected())
                            .build();
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<IncidentReportResponse> listIncidents(UUID organizationId, boolean unresolvedOnly,
                                                       Pageable pageable) {
        if (!orgIsolationGuard.belongsToOrg(organizationId)) {
            throw new BusinessException("Access denied: organization mismatch");
        }
        var page = unresolvedOnly
                ? incidentRepository.findByOrganizationIdAndResolvedAtIsNullOrderByTriggeredAtDesc(
                        organizationId, pageable)
                : incidentRepository.findByOrganizationIdOrderByTriggeredAtDesc(organizationId, pageable);
        return page.map(this::toIncidentResponse);
    }

    @Override
    public void cancelSos(UUID incidentId, UUID agentId) {
        IncidentReport incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));
        // Deliberately narrower than resolveIncident: only the agent who triggered this SOS may
        // cancel it themselves. Anyone else -- including other field officers in the same org --
        // must go through resolveIncident (supervisor-only, audited with resolution notes), not
        // this looser path. Otherwise any FO could silence a colleague's real emergency alert.
        if (!incident.getAgentId().equals(agentId)) {
            throw new ResourceNotFoundException("Incident not found: " + incidentId);
        }
        if (incident.getResolvedAt() != null) return;
        incident.setResolvedAt(Instant.now());
        incident.setResolvedByUserId(agentId);
        incident.setResolutionNotes("Cancelled by agent");
        incidentRepository.save(incident);
        sosAudioWebSocketHandler.relayEnd(incidentId);
        log.info("SOS cancelled by agent: incident={} agent={}", incidentId, agentId);
    }

    @Override
    public void storeSosAudio(UUID agentId, MultipartFile audio) {
        if (audio == null || audio.isEmpty()) throw new BusinessException("Audio file is required");
        String ext = getExtension(audio.getOriginalFilename());
        String filename = TS.format(Instant.now()) + "_" + agentId + ext;

        String s3Key = "sos-audio/" + agentId + "/" + filename;
        Path localPath = Paths.get(sosAudioPath, agentId.toString()).resolve(filename);
        try {
            byte[] bytes = audio.getBytes();
            String stored = storagePort.store(s3Key, localPath, new java.io.ByteArrayInputStream(bytes),
                    audio.getContentType() != null ? audio.getContentType() : "audio/m4a", audio.getSize());
            log.warn("SOS audio stored: location={} agent={}", stored, agentId);

            incidentRepository.findTopByAgentIdAndResolvedAtIsNullOrderByTriggeredAtDesc(agentId)
                    .ifPresent(incident -> sosAudioWebSocketHandler.relayAudioChunk(incident.getId(), bytes));
        } catch (IOException e) {
            throw new BusinessException("Could not save SOS audio recording: " + e.getMessage());
        }
    }

    private boolean isSupervisor() {
        return orgIsolationGuard.hasRole("ROLE_PLATFORM_ADMIN") || orgIsolationGuard.hasRole("ROLE_ORG_ADMIN")
                || orgIsolationGuard.hasRole("ROLE_MANAGER") || orgIsolationGuard.hasRole("ROLE_TL");
    }

    private String getExtension(String filename) {
        if (filename == null) return ".audio";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".audio";
    }

    private AgentShiftResponse toResponse(AgentShift s) {
        return AgentShiftResponse.builder()
                .id(s.getId())
                .agentId(s.getAgentId())
                .organizationId(s.getOrganizationId())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .status(s.getStatus())
                .build();
    }

    private IncidentReportResponse toIncidentResponse(IncidentReport i) {
        String agentName = userRepository.findById(i.getAgentId())
                .map(u -> u.getFirstName() + " " + u.getLastName())
                .orElse(null);
        return IncidentReportResponse.builder()
                .id(i.getId())
                .agentId(i.getAgentId())
                .agentName(agentName)
                .organizationId(i.getOrganizationId())
                .shiftId(i.getShiftId())
                .triggeredAt(i.getTriggeredAt())
                .lastKnownLat(i.getLastKnownLat())
                .lastKnownLng(i.getLastKnownLng())
                .lastKnownAccuracy(i.getLastKnownAccuracy())
                .recentPings(i.getRecentPings())
                .notes(i.getNotes())
                .resolvedAt(i.getResolvedAt())
                .resolvedByUserId(i.getResolvedByUserId())
                .resolutionNotes(i.getResolutionNotes())
                .createdAt(i.getCreatedAt())
                .build();
    }
}
