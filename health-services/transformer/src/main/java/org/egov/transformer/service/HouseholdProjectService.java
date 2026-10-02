package org.egov.transformer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.egov.common.models.project.Project;
import org.egov.transformer.config.TransformerProperties;
import org.egov.transformer.models.downstream.ProjectInfo;
import org.egov.transformer.utils.CommonUtils;
import org.springframework.stereotype.Service;

/**
 * Keeps track of the project a household belongs to.
 * <p>
 * Households carry no project of their own, so they are indexed with the first project of the user who registered
 * them. For users assigned to several campaigns that is often the wrong campaign. Delivery tasks always carry the
 * right project, so the task transformer confirms the household's project here, and the household and household
 * member transformers prefer a confirmed project over the user's.
 */
@Slf4j
@Service
public class HouseholdProjectService {
    private static final String CONFIRMED_KEY_PREFIX = "household-project-confirmed:";
    private static final String INDEXED_KEY_PREFIX = "household-project-indexed:";

    private final TransformerCacheService cacheService;
    private final ProjectService projectService;
    private final CommonUtils commonUtils;
    private final TransformerProperties transformerProperties;

    public HouseholdProjectService(TransformerCacheService cacheService, ProjectService projectService, CommonUtils commonUtils, TransformerProperties transformerProperties) {
        this.cacheService = cacheService;
        this.projectService = projectService;
        this.commonUtils = commonUtils;
        this.transformerProperties = transformerProperties;
    }

    /**
     * Project confirmed by a delivery task for this household, or null if none is known.
     */
    public ProjectInfo getConfirmedProject(String householdClientRefId, String tenantId) {
        if (!Boolean.TRUE.equals(transformerProperties.getHouseholdProjectCorrectionEnabled()) || householdClientRefId == null) {
            return null;
        }
        String projectId = cacheService.get(CONFIRMED_KEY_PREFIX + householdClientRefId, tenantId, String.class);
        if (projectId == null) {
            return null;
        }
        Project project = projectService.getProject(projectId, tenantId);
        return ObjectUtils.isNotEmpty(project) ? commonUtils.projectInfoFromProject(project) : null;
    }

    /**
     * Records the project a household was last indexed with.
     */
    public void recordIndexedProject(String householdClientRefId, String projectId, String tenantId) {
        if (!Boolean.TRUE.equals(transformerProperties.getHouseholdProjectCorrectionEnabled()) || householdClientRefId == null || projectId == null) {
            return;
        }
        cacheService.put(INDEXED_KEY_PREFIX + householdClientRefId, tenantId, projectId,
                transformerProperties.getHouseholdProjectCorrectionCacheTtlMinutes());
    }

    /**
     * Confirms the household's project from a delivery task.
     *
     * @return true if the household was last indexed with a different (or unknown) project and needs re-indexing
     */
    public boolean confirmProject(String householdClientRefId, String projectId, String tenantId) {
        Long ttl = transformerProperties.getHouseholdProjectCorrectionCacheTtlMinutes();
        String confirmed = cacheService.get(CONFIRMED_KEY_PREFIX + householdClientRefId, tenantId, String.class);
        if (projectId.equals(confirmed)) {
            return false;
        }
        cacheService.put(CONFIRMED_KEY_PREFIX + householdClientRefId, tenantId, projectId, ttl);
        String indexed = cacheService.get(INDEXED_KEY_PREFIX + householdClientRefId, tenantId, String.class);
        if (projectId.equals(indexed)) {
            return false;
        }
        log.info("Household {} was indexed with project {}, delivery task project is {}", householdClientRefId, indexed, projectId);
        return true;
    }
}
