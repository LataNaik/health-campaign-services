package org.egov.transformer.transformationservice;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.egov.common.models.household.Household;
import org.egov.common.models.household.HouseholdMember;
import org.egov.common.models.project.Project;
import org.egov.transformer.config.TransformerProperties;
import org.egov.transformer.service.HouseholdProjectService;
import org.egov.transformer.service.HouseholdService;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * Re-indexes a household and its members with the project of a delivery task, when it was indexed with another one.
 */
@Slf4j
@Component
public class HouseholdProjectCorrectionService {
    private final TransformerProperties transformerProperties;
    private final HouseholdProjectService householdProjectService;
    private final HouseholdService householdService;
    private final HouseholdTransformationService householdTransformationService;
    private final HouseholdMemberTransformationService householdMemberTransformationService;

    public HouseholdProjectCorrectionService(TransformerProperties transformerProperties, HouseholdProjectService householdProjectService, HouseholdService householdService, HouseholdTransformationService householdTransformationService, HouseholdMemberTransformationService householdMemberTransformationService) {
        this.transformerProperties = transformerProperties;
        this.householdProjectService = householdProjectService;
        this.householdService = householdService;
        this.householdTransformationService = householdTransformationService;
        this.householdMemberTransformationService = householdMemberTransformationService;
    }

    /**
     * @param householdClientRefId  household of the task, if known
     * @param individualClientRefId individual of the task, used to find the household when it is not known
     */
    public void correct(String householdClientRefId, String individualClientRefId, Project project, String tenantId) {
        if (!Boolean.TRUE.equals(transformerProperties.getHouseholdProjectCorrectionEnabled()) || project == null) {
            return;
        }
        // Never fail task indexing because of this
        try {
            String householdRef = householdClientRefId;
            if (householdRef == null && individualClientRefId != null) {
                List<HouseholdMember> members = householdService.searchHouseholdMembersByIndividual(individualClientRefId, tenantId);
                householdRef = CollectionUtils.isEmpty(members) ? null : members.get(0).getHouseholdClientReferenceId();
            }
            if (householdRef == null || !householdProjectService.confirmProject(householdRef, project.getId(), tenantId)) {
                return;
            }
            List<Household> households = householdService.searchHousehold(householdRef, tenantId);
            if (!CollectionUtils.isEmpty(households)) {
                householdTransformationService.transform(households);
            }
            List<HouseholdMember> members = householdService.searchHouseholdMembersByHousehold(householdRef, tenantId);
            if (!CollectionUtils.isEmpty(members)) {
                householdMemberTransformationService.transform(members);
            }
            log.info("Re-indexed household {} and {} members with project {} ({})", householdRef,
                    members.size(), project.getId(), project.getReferenceID());
        } catch (Exception e) {
            log.error("Error while correcting project of household {} / individual {}: {}", householdClientRefId,
                    individualClientRefId, ExceptionUtils.getStackTrace(e));
        }
    }
}
