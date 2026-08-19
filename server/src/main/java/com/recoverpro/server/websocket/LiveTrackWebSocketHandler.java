package com.recoverpro.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.security.jwt.JwtHandshakeInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Real-time field-officer location tracking WebSocket.
 *
 * Protocol (JSON text frames):
 *   FO → server:   { "type":"publish", "agentId", "lat", "lng", "accuracy",
 *                    "heading"?, "speed"?, "batteryLevel"?, "visitSessionId"?, "mockDetected"? }
 *   Web → server:  { "type":"subscribe" }
 *   Server → web:  { "type":"agent-update", "agentId", "lat", "lng", "accuracy",
 *                    "heading"?, "speed"?, "batteryLevel"?, "visitSessionId"?, "mockDetected"?, "ts" }
 *   Server → web:  { "type":"agent-offline", "agentId" }
 *
 * Fan-out:
 *   1. In-process broadcast to local WS subscribers (single-node fast path).
 *   2. Redis PUBLISH livetrack:pos:{orgId} → cross-pod fan-out via LiveTrackRedisSubscriber.
 */
@Slf4j
@Component
public class LiveTrackWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final String podInstanceId;

    public LiveTrackWebSocketHandler(ObjectMapper objectMapper,
                                     StringRedisTemplate stringRedisTemplate,
                                     @Qualifier("podInstanceId") String podInstanceId) {
        this.objectMapper        = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.podInstanceId       = podInstanceId;
    }

    // Web supervisor sessions — keyed by orgId for tenant-scoped broadcast
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketSession>> subscribersByOrg =
            new ConcurrentHashMap<>();

    // agentId → last published snapshot (for snapshot on new subscribe)
    private final ConcurrentHashMap<String, AgentSnapshot> lastPositions = new ConcurrentHashMap<>();

    // WS sessionId → agentId (for cleanup on disconnect)
    private final ConcurrentHashMap<String, String> publisherRegistry = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UserPrincipal principal = principalOrClose(session);
        if (principal == null) return;
        JsonNode node = objectMapper.readTree(message.getPayload());
        switch (node.path("type").asText()) {
            case "publish"   -> handlePublish(session, principal, node);
            case "subscribe" -> handleSubscribe(session, principal);
            default          -> { }
        }
    }

    private void handlePublish(WebSocketSession session, UserPrincipal principal, JsonNode node) {
        String agentId = node.path("agentId").asText();
        if (agentId.isBlank()) return;

        // Anti-spoof: only the authenticated agent may publish their own location
        if (!agentId.equals(principal.getId().toString())) {
            log.warn("LiveTrack publish rejected: principal={} tried to spoof agent={}",
                    principal.getId(), agentId);
            return;
        }
        publisherRegistry.put(session.getId(), agentId);

        double  lat            = node.path("lat").asDouble();
        double  lng            = node.path("lng").asDouble();
        double  accuracy       = node.path("accuracy").asDouble(0);
        Double  heading        = optionalDouble(node, "heading");
        Double  speed          = optionalDouble(node, "speed");
        String  agentName      = node.path("agentName").asText(null);
        String  visitSessionId = node.path("visitSessionId").isMissingNode() ? null
                               : node.path("visitSessionId").asText(null);
        Double  batteryLevel   = optionalDouble(node, "batteryLevel");
        boolean mockDetected   = node.path("mockDetected").asBoolean(false);

        lastPositions.put(agentId, new AgentSnapshot(principal.getOrganizationId(),
                lat, lng, accuracy, heading, speed, agentName, visitSessionId, batteryLevel, mockDetected));

        ObjectNode relay = buildUpdateNode(agentId, lat, lng, accuracy, heading, speed,
                agentName, visitSessionId, batteryLevel, mockDetected);

        // 1. In-process fan-out (fast path)
        broadcast(principal.getOrganizationId(), relay);

        // 2. Cross-pod fan-out via Redis Pub/Sub
        redisPublish(principal.getOrganizationId(), relay);
    }

    private void handleSubscribe(WebSocketSession session, UserPrincipal principal) {
        UUID orgId = principal.getOrganizationId();
        subscribersByOrg.computeIfAbsent(orgId, k -> new CopyOnWriteArraySet<>()).add(session);

        // Push snapshot of all currently-active agents in the subscriber's org
        lastPositions.forEach((agentId, snap) -> {
            if (!orgId.equals(snap.orgId())) return;
            ObjectNode msg = buildUpdateNode(agentId, snap.lat(), snap.lng(), snap.accuracy(),
                    snap.heading(), snap.speed(), snap.agentName(),
                    snap.visitSessionId(), snap.batteryLevel(), snap.mockDetected());
            try {
                if (session.isOpen())
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            } catch (IOException ignored) {}
        });
        log.info("Supervisor subscribed to live-track: session={} org={}", session.getId(), orgId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribersByOrg.values().forEach(set -> set.remove(session));
        String agentId = publisherRegistry.remove(session.getId());
        if (agentId != null) {
            AgentSnapshot removed = lastPositions.remove(agentId);
            log.info("Live-track agent offline: agentId={}", agentId);
            if (removed != null) {
                ObjectNode offline = objectMapper.createObjectNode();
                offline.put("type", "agent-offline");
                offline.put("agentId", agentId);
                offline.put("origin", podInstanceId);
                broadcast(removed.orgId(), offline);
                redisPublish(removed.orgId(), offline);
            }
        }
    }

    /**
     * Called from AgentFieldServiceImpl after each REST ping is persisted.
     * Callers MUST pass the agent's organizationId for tenant isolation.
     */
    public void relayAgentLocation(UUID agentId, UUID orgId, double lat, double lng,
                                   double accuracy, String agentName) {
        String id = agentId.toString();
        lastPositions.put(id, new AgentSnapshot(orgId, lat, lng, accuracy, null, null, agentName, null, null, false));
        ObjectNode relay = buildUpdateNode(id, lat, lng, accuracy, null, null, agentName, null, null, false);
        broadcast(orgId, relay);
        redisPublish(orgId, relay);
    }

    /**
     * Called by LiveTrackRedisSubscriber when a live-track message published by a
     * *different* pod (origin already checked by the caller) arrives via Redis.
     * Updates this pod's own snapshot cache (so a supervisor who newly subscribes
     * here still gets an accurate initial snapshot regardless of which pod last
     * handled that agent's ping) and delivers to this pod's local subscribers only
     * -- never republishes to Redis, which would echo the message back and forth
     * between pods indefinitely.
     */
    public void deliverFromRedis(UUID orgId, JsonNode node, String rawJson) {
        String type = node.path("type").asText();
        if ("agent-update".equals(type)) {
            String agentId = node.path("agentId").asText();
            lastPositions.put(agentId, new AgentSnapshot(orgId,
                    node.path("lat").asDouble(), node.path("lng").asDouble(), node.path("accuracy").asDouble(0),
                    optionalDouble(node, "heading"), optionalDouble(node, "speed"),
                    node.path("agentName").asText(null),
                    node.path("visitSessionId").isMissingNode() ? null : node.path("visitSessionId").asText(null),
                    optionalDouble(node, "batteryLevel"), node.path("mockDetected").asBoolean(false)));
        } else if ("agent-offline".equals(type)) {
            lastPositions.remove(node.path("agentId").asText());
        }
        broadcast(orgId, rawJson);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void redisPublish(UUID orgId, ObjectNode node) {
        try {
            stringRedisTemplate.convertAndSend(
                    LiveTrackRedisSubscriber.CHANNEL_PREFIX + orgId,
                    objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("LiveTrack Redis publish failed (non-fatal): {}", e.getMessage());
        }
    }

    private UserPrincipal principalOrClose(WebSocketSession session) {
        Object attr = session.getAttributes().get(JwtHandshakeInterceptor.PRINCIPAL_ATTR);
        if (attr instanceof UserPrincipal p) return p;
        try { session.close(CloseStatus.POLICY_VIOLATION.withReason("missing principal")); }
        catch (IOException ignored) {}
        return null;
    }

    private ObjectNode buildUpdateNode(String agentId, double lat, double lng,
                                       double accuracy, Double heading, Double speed,
                                       String agentName, String visitSessionId,
                                       Double batteryLevel, boolean mockDetected) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type",     "agent-update");
        n.put("agentId",  agentId);
        n.put("lat",      lat);
        n.put("lng",      lng);
        n.put("accuracy", accuracy);
        if (heading        != null) n.put("heading",        heading);
        if (speed          != null) n.put("speed",          speed);
        if (agentName      != null && !agentName.isBlank()) n.put("agentName", agentName);
        if (visitSessionId != null) n.put("visitSessionId", visitSessionId);
        if (batteryLevel   != null) n.put("batteryLevel",   batteryLevel);
        if (mockDetected)            n.put("mockDetected",   true);
        n.put("ts", System.currentTimeMillis());
        n.put("origin", podInstanceId);
        return n;
    }

    private void broadcast(UUID orgId, ObjectNode node) {
        // SYSTEM 13 TASK 13.2: was silent -- matches SosAudioWebSocketHandler's identical-purpose
        // method, which already logs this same failure mode.
        try {
            broadcast(orgId, objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("LiveTrack relay serialization failed: {}", e.getMessage());
        }
    }

    private void broadcast(UUID orgId, String json) {
        CopyOnWriteArraySet<WebSocketSession> subs = subscribersByOrg.get(orgId);
        if (subs == null || subs.isEmpty()) return;
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession sub : subs) {
            if (sub.isOpen()) { try { sub.sendMessage(msg); } catch (IOException ignored) {} }
        }
    }

    private Double optionalDouble(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        double v = n.asDouble();
        return v >= 0 ? v : null;
    }

    private record AgentSnapshot(UUID orgId, double lat, double lng, double accuracy,
                                  Double heading, Double speed, String agentName,
                                  String visitSessionId, Double batteryLevel, boolean mockDetected) {}
}
