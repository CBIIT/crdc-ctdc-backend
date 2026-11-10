package gov.nih.nci.bento;

import gov.nih.nci.bento.model.search.filter.FilterParam;
import gov.nih.nci.bento.model.search.query.QueryFactory;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.TermsQueryBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClinicalTrialDataTherapyCategoryRegressionTest {

    private static final String YAML_PATH = "src/main/resources/yaml/es_indices_ctdc.yml";

    @Test
    public void therapyProjections_preserveFacetCaseAndTrimValues() throws IOException {
        Map<?, ?> yaml = new Yaml().load(Files.readString(Paths.get(YAML_PATH), StandardCharsets.UTF_8));
        List<?> indices = (List<?>) yaml.get("Indices");
        for (String name : List.of("tab_biospecimens", "biospecimen_data_file", "study_file_overview")) {
            Map<?, ?> index = indices.stream().map(Map.class::cast)
                    .filter(entry -> name.equals(entry.get("index_name"))).findFirst().orElseThrow();
            String query = ((String) index.get("cypher_query")).replaceAll("\\s+", " ");
            assertFalse(query.contains("toLower(trim(toString("),
                    name + " must preserve therapy facet casing");
            assertTrue(query.contains("AS therapy_string"),
                    name + " must retain therapy combination filtering");
            assertTrue(query.contains("| trim(toString("),
                    name + " must trim therapy values");
            if ("biospecimen_data_file".equals(name)) {
                assertTrue(query.contains(
                        "apoc.text.capitalizeAll(toLower(spec.anatomical_collection_site)) AS anatomical_collection_site"),
                        "Biospecimen files must use title-cased anatomical sites");
                assertTrue(query.contains(
                        "apoc.coll.sort([t IN collect(DISTINCT tt.therapy_name) WHERE t IS NOT NULL AND trim(toString(t)) <> '' | trim(toString(t)) ])"),
                        "Biospecimen file combinations must preserve therapy name casing");
            } else {
                assertTrue(query.contains(
                        "WITH [x IN COLLECT(tt.therapy_name) WHERE x IS NOT NULL AND trim(toString(x)) <> '' | trim(toString(x)) ] AS ts"),
                        name + " must preserve therapy name casing before constructing combinations");
            }
        }

        Map<?, ?> therapyCount = indices.stream().map(Map.class::cast)
                .filter(index -> "therapy_count".equals(index.get("index_name")))
                .findFirst().orElseThrow();
        String query = ((String) therapyCount.get("cypher_query")).replaceAll("\\s+", " ");
        assertTrue(query.contains("COLLECT(DISTINCT trim(toString(target.therapy_name))) as therapy_name"));
        for (Map.Entry<String, String> field : Map.of(
                "best_response_to_therapy", "r",
                "current_response_to_therapy", "s",
                "therapy_type", "t",
                "therapy_category", "c").entrySet()) {
            String variable = field.getValue();
            assertTrue(query.contains("apoc.coll.toSet([" + variable + " IN COLLECT(target." + field.getKey()
                    + ") WHERE " + variable + " IS NOT NULL AND trim(toString(" + variable + ")) <> ''"
                    + " | trim(toString(" + variable + "))]) as " + field.getKey()),
                    "Therapy counts must trim and deduplicate nonblank " + field.getKey() + " values");
        }
    }

    @Test
    public void therapyCountAnatomicalSites_matchFacetNormalization() throws IOException {
        Map<?, ?> yaml = new Yaml().load(Files.readString(Paths.get(YAML_PATH), StandardCharsets.UTF_8));
        List<?> indices = (List<?>) yaml.get("Indices");
        Map<?, ?> therapyCount = indices.stream().map(Map.class::cast)
                .filter(index -> "therapy_count".equals(index.get("index_name")))
                .findFirst().orElseThrow();
        Map<?, ?> mapping = (Map<?, ?>) therapyCount.get("mapping");
        assertEquals("keyword", ((Map<?, ?>) mapping.get("anatomical_collection_site")).get("type"));
        String query = (String) therapyCount.get("cypher_query");
        assertTrue(query.contains(
                "COLLECT(DISTINCT apoc.text.capitalizeAll(toLower(spec.anatomical_collection_site))) AS anatomical_collection_site"),
                "Therapy counts must index title-cased sites to match anatomical-site facet selections");
        assertTrue(query.contains(
                "anatomical_collection_site: apoc.text.capitalizeAll(toLower(spec.anatomical_collection_site))"),
                "Nested biospecimen sites must use the same normalized values");

        Map<?, ?> widgets = indices.stream().map(Map.class::cast)
                .filter(index -> "widgets_facets_counts".equals(index.get("index_name")))
                .findFirst().orElseThrow();
        assertTrue(((String) widgets.get("cypher_query")).contains(
                "apoc.text.capitalizeAll(toLower(s))"),
                "Participant therapy facets and therapy totals must use the same site normalization");
    }

    @Test
    public void therapyFacetFilters_keepAnatomicalSiteSelection() {
        for (String selectedField : List.of("therapy_name", "therapy_category")) {
            for (boolean ignoreSelectedField : List.of(false, true)) {
                FilterParam param = FilterParam.builder()
                        .args(Map.of("anatomical_collection_site", List.of("Abdominal Wall"),
                                selectedField, List.of("selected therapy")))
                        .selectedField(selectedField)
                        .isIgnoreSelectedField(ignoreSelectedField)
                        .build();
                BoolQueryBuilder query = (BoolQueryBuilder) new QueryFactory(param).getQuery();
                TermsQueryBuilder site = query.filter().stream().map(TermsQueryBuilder.class::cast)
                        .filter(filter -> "anatomical_collection_site".equals(filter.fieldName()))
                        .findFirst().orElseThrow();
                assertEquals(List.of("Abdominal Wall"), site.values());
                assertEquals(ignoreSelectedField ? 1 : 2, query.filter().size(),
                        "Self-excluding therapy facets must still apply the anatomical-site filter");
            }
        }
    }

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
                clinicalTrialDataBlock.contains("[t IN unique_th WHERE trim(toString(COALESCE(t.therapy_category, ''))) = 'Non-Targeted' | apoc.map.clean({"),
                "nonTargetedTherapyNodeData must only project non-targeted therapy nodes"
        );
        assertTrue(
                clinicalTrialDataBlock.contains("non_targeted_therapy: toString(COALESCE(t.therapy_name, ''))"),
                "The legacy non-targeted therapy output must use the shared therapy_name property"
        );
        assertFalse(clinicalTrialDataBlock.contains("t.non_targeted_therapy_name"),
                "Therapy nodes no longer have a separate non-targeted therapy name");
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
        assertTrue(therapyCountBlock.contains("therapy_name: trim(toString(target.therapy_name))"),
                "therapy objects should expose the shared therapy_name field");
        assertTrue(therapyCountBlock.contains("COLLECT(DISTINCT array) as therapy_string"),
                "therapy_count should populate therapy_string from the locally joined therapy combination");
        assertFalse(therapyCountBlock.contains("COLLECT(DISTINCT target.array) as therapy_string"),
                "therapy_count should not read therapy_string from a non-existent target.array property");
        assertFalse(therapyCountBlock.contains("COLLECT(DISTINCT trim(toString(target.therapy_name))) as therapy,"),
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
