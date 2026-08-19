package com.recoverpro.server.service;

import com.recoverpro.server.dto.response.OnboardingChecklistResponse;

import java.util.UUID;

/** TASK 28.3: guided-activation checklist. */
public interface OnboardingService {

    OnboardingChecklistResponse getChecklist(UUID organizationId);
}
