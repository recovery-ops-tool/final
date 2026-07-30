package com.recoverpro.server.service;

import com.recoverpro.server.dto.request.ConfirmVisitActionRequest;
import com.recoverpro.server.dto.response.ChatResponse;
import com.recoverpro.server.security.UserPrincipal;
import org.springframework.web.multipart.MultipartFile;

/**
 * Confirms (or cancels) the submit_visit_interview WRITE tool for a Lucien visit-interview
 * session — a dedicated multipart path alongside LucienService/ConfirmationService's generic
 * plain-JSON confirm, since this is the one tool whose write needs GPS + photos that were
 * deliberately kept out of the model's hands.
 */
public interface VisitInterviewService {

    ChatResponse confirm(String sessionId, ConfirmVisitActionRequest request,
                          MultipartFile image1, MultipartFile image2, UserPrincipal principal);
}
