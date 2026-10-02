package org.egov.transformer.transformationservice;

import org.egov.common.models.household.Household;
import org.egov.common.models.household.HouseholdMember;
import org.egov.common.models.project.Project;
import org.egov.transformer.config.TransformerProperties;
import org.egov.transformer.service.HouseholdProjectService;
import org.egov.transformer.service.HouseholdService;
import org.egov.transformer.service.ProjectService;
import org.egov.transformer.service.TransformerCacheService;
import org.egov.transformer.utils.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HouseholdProjectCorrectionServiceTest {
    private static final String TENANT = "demo";
    private static final String HOUSEHOLD = "hh-1";

    private final Map<String, Object> redis = new HashMap<>();
    private TransformerProperties properties;
    private HouseholdService householdService;
    private HouseholdTransformationService householdTransformationService;
    private HouseholdMemberTransformationService householdMemberTransformationService;
    private HouseholdProjectService householdProjectService;
    private HouseholdProjectCorrectionService correctionService;

    @BeforeEach
    void setUp() {
        TransformerCacheService cacheService = mock(TransformerCacheService.class);
        doAnswer(i -> redis.put(i.<String>getArgument(1) + i.<String>getArgument(0), i.getArgument(2)))
                .when(cacheService).put(anyString(), anyString(), any(), anyLong());
        when(cacheService.get(anyString(), anyString(), eq(String.class)))
                .thenAnswer(i -> redis.get(i.<String>getArgument(1) + i.<String>getArgument(0)));

        properties = new TransformerProperties();
        properties.setHouseholdProjectCorrectionEnabled(true);
        properties.setHouseholdProjectCorrectionCacheTtlMinutes(60L);

        householdService = mock(HouseholdService.class);
        householdTransformationService = mock(HouseholdTransformationService.class);
        householdMemberTransformationService = mock(HouseholdMemberTransformationService.class);
        householdProjectService = new HouseholdProjectService(cacheService, mock(ProjectService.class), mock(CommonUtils.class), properties);
        correctionService = new HouseholdProjectCorrectionService(properties, householdProjectService, householdService,
                householdTransformationService, householdMemberTransformationService);

        when(householdService.searchHousehold(HOUSEHOLD, TENANT)).thenReturn(Collections.singletonList(new Household()));
        when(householdService.searchHouseholdMembersByHousehold(HOUSEHOLD, TENANT))
                .thenReturn(Collections.singletonList(new HouseholdMember()));
    }

    private static Project project(String id) {
        Project project = new Project();
        project.setId(id);
        project.setReferenceID("CMP-" + id);
        return project;
    }

    @Test
    void reindexesHouseholdAndMembersIndexedWithAnotherProject() {
        householdProjectService.recordIndexedProject(HOUSEHOLD, "bednet", TENANT);

        correctionService.correct(HOUSEHOLD, null, project("mrdn"), TENANT);

        verify(householdTransformationService).transform(anyList());
        verify(householdMemberTransformationService).transform(anyList());
        assertEquals("mrdn", redis.get(TENANT + "household-project-confirmed:" + HOUSEHOLD));
    }

    @Test
    void skipsHouseholdAlreadyIndexedWithTheTaskProject() {
        householdProjectService.recordIndexedProject(HOUSEHOLD, "mrdn", TENANT);

        correctionService.correct(HOUSEHOLD, null, project("mrdn"), TENANT);

        verifyNoInteractions(householdTransformationService, householdMemberTransformationService);
    }

    @Test
    void reindexesOnlyOnceForRepeatedTasks() {
        householdProjectService.recordIndexedProject(HOUSEHOLD, "bednet", TENANT);

        correctionService.correct(HOUSEHOLD, null, project("mrdn"), TENANT);
        correctionService.correct(HOUSEHOLD, null, project("mrdn"), TENANT);

        verify(householdTransformationService, times(1)).transform(anyList());
    }

    @Test
    void findsHouseholdThroughMemberForIndividualTasks() {
        HouseholdMember member = new HouseholdMember();
        member.setHouseholdClientReferenceId(HOUSEHOLD);
        when(householdService.searchHouseholdMembersByIndividual("ind-1", TENANT)).thenReturn(Collections.singletonList(member));
        householdProjectService.recordIndexedProject(HOUSEHOLD, "bednet", TENANT);

        correctionService.correct(null, "ind-1", project("mrdn"), TENANT);

        verify(householdService).searchHousehold(HOUSEHOLD, TENANT);
        verify(householdTransformationService).transform(anyList());
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setHouseholdProjectCorrectionEnabled(false);

        correctionService.correct(HOUSEHOLD, null, project("mrdn"), TENANT);

        verifyNoInteractions(householdService, householdTransformationService, householdMemberTransformationService);
    }

    @Test
    void neverThrowsIntoTaskIndexing() {
        when(householdService.searchHousehold(HOUSEHOLD, TENANT)).thenThrow(new RuntimeException("household service down"));

        correctionService.correct(HOUSEHOLD, null, project("mrdn"), TENANT);

        verifyNoInteractions(householdTransformationService);
    }
}
