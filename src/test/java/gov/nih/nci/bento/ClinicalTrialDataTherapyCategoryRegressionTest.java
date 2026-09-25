package gov.nih.nci.bento;

import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClinicalTrialDataTherapyCategoryRegressionTest {

    private static final String YAML_PATH = "src/main/resources/yaml/es_indices_ctdc.yml";

    @Test
    public void clinicalTrialDataCypher_shouldRemoveDeadTargetedProjectionAndKeepNonTargetedScoped() throws IOException {
        String yaml = Files.readString(Paths.get(YAML_PATH), StandardCharsets.UTF_8);

        String blockStart = "- index_name: clinical_trial_data_tab";
        String blockEnd = "- index_name: about_page";
        int start = yaml.indexOf(blockStart);
        int end = yaml.indexOf(blockEnd);

        assertTrue(start >= 0, "clinical_trial_data_tab block must exist");
        assertTrue(end > start, "about_page block must exist after clinical_trial_data_tab");

        String clinicalTrialDataBlock = yaml.substring(start, end);

        assertFalse(clinicalTrialDataBlock.contains("targetedTherapyNodeData"),
                "clinical_trial_data_tab should not retain targetedTherapyNodeData");
        assertFalse(clinicalTrialDataBlock.contains("targetedTherapyNodeCount"),
                "clinical_trial_data_tab should not retain targetedTherapyNodeCount");
        assertFalse(clinicalTrialDataBlock.contains("targetedTherapyParticipantCount"),
                "clinical_trial_data_tab should not retain targetedTherapyParticipantCount");
        assertFalse(clinicalTrialDataBlock.contains("AS targetedTherapyNodeData"),
                "clinical_trial_data_tab should not retain the targeted therapy projection");
        assertFalse(clinicalTrialDataBlock.contains("AS targetedTherapyParticipantCount"),
                "clinical_trial_data_tab should not retain the targeted therapy participant count");
        assertTrue(
                clinicalTrialDataBlock.contains("[t IN unique_th WHERE trim(toString(COALESCE(t.therapy_category, ''))) = 'Non-Targeted' | {"),
                "nonTargetedTherapyNodeData must only project non-targeted therapy nodes"
        );
        assertTrue(
                clinicalTrialDataBlock.contains("trim(toString(COALESCE(x.node.therapy_category, ''))) = 'Non-Targeted' | x.participant_id])) AS nonTargetedTherapyParticipantCount"),
                "nonTargetedTherapyParticipantCount must only count non-targeted therapy participants"
        );
        assertTrue(
                clinicalTrialDataBlock.contains("therapy_type: toString(COALESCE(t.therapy_type, ''))"),
                "therapyNodeData should emit therapy_type from the therapy node"
        );
        assertFalse(
                clinicalTrialDataBlock.contains("type: toString(COALESCE(t.type, ''))"),
                "therapyNodeData should not expose the stale generic type projection"
        );

        int studyFileOverviewStart = yaml.indexOf("- index_name: study_file_overview");
        int gsListStart = yaml.indexOf("- index_name: gs_list");
        assertTrue(studyFileOverviewStart >= 0, "study_file_overview block should exist");
        assertTrue(gsListStart > studyFileOverviewStart, "gs_list should follow study_file_overview");

        String studyFileOverviewBlock = yaml.substring(studyFileOverviewStart, gsListStart);
        assertTrue(studyFileOverviewBlock.contains("[x IN COLLECT(tt.therapy_type)"),
                "study_file_overview should collect therapy_type values");
        assertTrue(studyFileOverviewBlock.contains("[x IN COLLECT(tt.best_response_to_therapy)"),
                "study_file_overview should collect best_response_to_therapy values");
        assertTrue(studyFileOverviewBlock.contains("[x IN COLLECT(tt.current_response_to_therapy)"),
                "study_file_overview should collect current_response_to_therapy values");
        assertTrue(studyFileOverviewBlock.contains("therapy_type AS therapy_type"),
                "study_file_overview should return therapy_type values");
        assertTrue(studyFileOverviewBlock.contains("best_response_to_therapy AS best_response_to_therapy"),
                "study_file_overview should return best_response_to_therapy values");
        assertTrue(studyFileOverviewBlock.contains("current_response_to_therapy AS current_response_to_therapy"),
                "study_file_overview should return current_response_to_therapy values");
    }

    @Test
    public void participantTherapyCypher_returnsTherapyObjects() throws IOException {
        String yaml = Files.readString(Paths.get(YAML_PATH), StandardCharsets.UTF_8);

        int therapyCountStart = yaml.indexOf("- index_name: therapy_count");
        int studyNodeCountStart = yaml.indexOf("- index_name: study_node_counts");

        assertTrue(therapyCountStart >= 0, "therapy_count block must exist");
        assertTrue(studyNodeCountStart > therapyCountStart, "study_node_counts must follow therapy_count");

        String therapyCountBlock = yaml.substring(therapyCountStart, studyNodeCountStart);

        assertTrue(therapyCountBlock.contains("COLLECT(DISTINCT {"),
                "therapy_count should emit therapy objects rather than plain strings");
        assertTrue(therapyCountBlock.contains("therapy_record_id: target.therapy_record_id"),
                "therapy objects should include therapy_record_id");
        assertTrue(therapyCountBlock.contains("therapy: trim(toString(target.therapy_name))"),
                "therapy objects should include the therapy name");
        assertTrue(therapyCountBlock.contains("COLLECT(DISTINCT array) as therapy_string"),
                "therapy_count should populate therapy_string from the locally joined therapy combination");
        assertFalse(therapyCountBlock.contains("COLLECT(DISTINCT target.array) as therapy_string"),
                "therapy_count should not read therapy_string from a non-existent target.array property");
        assertFalse(therapyCountBlock.contains("COLLECT(DISTINCT trim(toString(target.therapy_name))) as therapy"),
                "therapy_count should not return therapy as a plain string list");
    }

    @Test
    public void clinicalTrialDataSchema_onlyExposesNonTargetedLegacyProjection() throws Exception {
        String schema;
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(inputStream, "GraphQL schema should be present");
            schema = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);
        Optional<ObjectTypeDefinition> clinicalTrialDataType = registry.getType("ClinicalTrialData", ObjectTypeDefinition.class);

        assertTrue(clinicalTrialDataType.isPresent(), "ClinicalTrialData type should exist");
        assertTrue(
                clinicalTrialDataType.get().getFieldDefinitions().stream()
                        .anyMatch(fieldDefinition -> "nonTargetedTherapyNodeData".equals(fieldDefinition.getName())),
                "ClinicalTrialData should still expose nonTargetedTherapyNodeData"
        );
        assertFalse(
                clinicalTrialDataType.get().getFieldDefinitions().stream()
                        .anyMatch(fieldDefinition -> "targetedTherapyNodeData".equals(fieldDefinition.getName())),
                "ClinicalTrialData should not expose targetedTherapyNodeData"
        );

        Optional<ObjectTypeDefinition> clinicalTherapyType = registry.getType("ClinicalTherapy", ObjectTypeDefinition.class);
        assertTrue(clinicalTherapyType.isPresent(), "ClinicalTherapy type should exist");

        Set<String> clinicalTherapyFields = clinicalTherapyType.get().getFieldDefinitions().stream()
                .map(fieldDefinition -> fieldDefinition.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(
                clinicalTherapyFields.containsAll(Set.of(
                        "therapy_category",
                        "therapy_type",
                        "best_response_to_therapy",
                        "course_number",
                        "current_response_to_therapy",
                        "date_of_best_response_to_therapy",
                        "date_of_current_response_to_therapy",
                        "dose_changes_delays",
                        "dose_changes_delays_description",
                        "number_of_doses",
                        "off_treatment",
                        "off_treatment_reason",
                        "planned_dose",
                        "planned_dose_units",
                        "therapy_description",
                        "therapy_dose_units",
                        "therapy_start_date",
                        "therapy_end_date"
                )),
                "ClinicalTherapy should expose the indexed therapy node attributes"
        );
    }
}
