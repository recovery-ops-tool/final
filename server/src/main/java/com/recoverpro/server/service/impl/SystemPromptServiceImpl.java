package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.UpdateSystemPromptRequest;
import com.recoverpro.server.dto.response.SystemPromptResponse;
import com.recoverpro.server.entity.SystemPromptConfig;
import com.recoverpro.server.prompt.DefaultSystemPrompt;
import com.recoverpro.server.repository.SystemPromptConfigRepository;
import com.recoverpro.server.service.SystemPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemPromptServiceImpl implements SystemPromptService {

    private final SystemPromptConfigRepository systemPromptConfigRepository;

    @Override
    @Transactional(readOnly = true)
    public SystemPromptResponse getActivePrompt(String promptKey) {
        return toResponse(findActiveConfig(promptKey));
    }

    @Override
    @Transactional
    @CacheEvict(value = "systemPrompts", key = "#promptKey")
    public SystemPromptResponse updatePrompt(String promptKey, UpdateSystemPromptRequest request, UUID updatedBy) {
        log.info("Updating system prompt '{}' by user {}", promptKey, updatedBy);
        SystemPromptConfig config;

        if (systemPromptConfigRepository.existsByPromptKey(promptKey)) {
            config = findActiveConfig(promptKey);
            config.setPromptTemplate(request.getPromptTemplate());
            config.setDescription(request.getDescription());
            config.setVersion(config.getVersion() + 1);
            config.setUpdatedBy(updatedBy);
        } else {
            config = SystemPromptConfig.builder()
                    .promptKey(promptKey)
                    .promptTemplate(request.getPromptTemplate())
                    .description(request.getDescription())
                    .version(1)
                    .isActive(true)
                    .updatedBy(updatedBy)
                    .build();
        }

        SystemPromptConfig saved = systemPromptConfigRepository.save(config);
        log.info("System prompt '{}' updated to version {}", promptKey, saved.getVersion());
        return toResponse(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = "systemPrompts", key = "#promptKey")
    public void deletePrompt(String promptKey) {
        systemPromptConfigRepository.findByPromptKey(promptKey)
                .ifPresent(config -> {
                    systemPromptConfigRepository.delete(config);
                    log.info("Deleted system prompt '{}'", promptKey);
                });
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "systemPrompts", key = "#promptKey")
    public String resolveActiveTemplate(String promptKey) {
        return systemPromptConfigRepository.findByPromptKeyAndIsActiveTrue(promptKey)
                .map(SystemPromptConfig::getPromptTemplate)
                .orElseGet(() -> {
                    log.warn("No active DB prompt found for key '{}'. Falling back to built-in default.", promptKey);
                    return defaultTemplateFor(promptKey);
                });
    }

    /** Each promptKey must fall back to ITS OWN built-in template, not always the general
     * assistant one - otherwise a visit-interview session with no DB override would silently
     * run the wrong (general) prompt. */
    private String defaultTemplateFor(String promptKey) {
        if (DefaultSystemPrompt.INTERVIEW_KEY.equals(promptKey)) {
            return DefaultSystemPrompt.INTERVIEW_TEMPLATE;
        }
        return DefaultSystemPrompt.TEMPLATE;
    }

    private SystemPromptConfig findActiveConfig(String promptKey) {
        return systemPromptConfigRepository.findByPromptKeyAndIsActiveTrue(promptKey)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active system prompt found for key: " + promptKey));
    }

    private SystemPromptResponse toResponse(SystemPromptConfig config) {
        return SystemPromptResponse.builder()
                .id(config.getId())
                .promptKey(config.getPromptKey())
                .promptTemplate(config.getPromptTemplate())
                .version(config.getVersion())
                .isActive(config.getIsActive())
                .description(config.getDescription())
                .updatedBy(config.getUpdatedBy())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
