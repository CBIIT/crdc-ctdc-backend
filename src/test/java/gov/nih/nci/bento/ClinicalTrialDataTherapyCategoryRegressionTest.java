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
    }
}
