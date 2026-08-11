package gov.nih.nci.bento;

import graphql.language.ObjectTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SurvivalStatusQueryConfigurationTest {

    @Test
    public void fileAndBiospecimenSchemaTypesExposeSurvivalStatus() throws Exception {
        String schema;
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(inputStream, "GraphQL schema should be present");
            schema = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);
        assertTypeExposesField(registry, "BiospecimenOverview", "survival_status");
        assertTypeExposesField(registry, "FileOverview", "survival_status");
        assertTypeExposesField(registry, "FileParticipant", "survival_status");
        assertTypeExposesField(registry, "StudyFileOverviewInfo", "survival_status");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tabBiospecimensPropagatesStudyAccession() {
        Map<String, Object> yamlConfig;
        Yaml yaml = new Yaml();
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("yaml/es_indices_ctdc.yml")) {
            assertNotNull(inputStream, "Index configuration should be present");
            yamlConfig = yaml.load(inputStream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load index configuration", e);
        }

        List<Map<String, Object>> indices = (List<Map<String, Object>>) yamlConfig.get("Indices");
        assertNotNull(indices, "Indices should be present in the YAML configuration");

        Map<String, Object> index = indices.stream()
                .filter(candidate -> "tab_biospecimens".equals(candidate.get("index_name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tab_biospecimens index is missing"));

        Map<String, Object> mapping = (Map<String, Object>) index.get("mapping");
        assertTrue(mapping.containsKey("study_accession"), "tab_biospecimens mapping should contain study_accession");
        assertEquals("keyword", ((Map<String, Object>) mapping.get("study_accession")).get("type"));

        String cypherQuery = (String) index.get("cypher_query");
        assertTrue(cypherQuery.contains("study.study_accession AS study_accession"),
                "tab_biospecimens cypher should return study_accession");
    }

    @Test
    public void yamlDefinedQueryArgumentsExposeSurvivalStatus() throws Exception {
        String schema;
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(inputStream, "GraphQL schema should be present");
            schema = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);
        Optional<ObjectTypeDefinition> queryType = registry.getType("QueryType", ObjectTypeDefinition.class);
        assertTrue(queryType.isPresent(), "QueryType should exist");

        assertQueryArgumentExists(queryType.get(), "StudyFileOverviewByStudyShortName", "survival_status");
        assertQueryArgumentExists(queryType.get(), "studyFileOverview", "survival_status");
        assertQueryArgumentExists(queryType.get(), "participantOverview", "survival_status");
        assertQueryArgumentExists(queryType.get(), "biospecimenOverview", "survival_status");
        assertQueryArgumentExists(queryType.get(), "fileOverview", "survival_status");
        assertQueryArgumentExists(queryType.get(), "participant_data_files", "survival_status");
        assertQueryArgumentExists(queryType.get(), "biospecimen_data_files", "survival_status");
        assertQueryArgumentExists(queryType.get(), "searchParticipants", "survival_status");
        assertQueryArgumentExists(queryType.get(), "filesInList", "survival_status");
        assertQueryArgumentExists(queryType.get(), "fileIDsFromList", "survival_status");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void yamlIndicesPropagateSurvivalStatus() {
        Map<String, Object> yamlConfig;
        Yaml yaml = new Yaml();
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("yaml/es_indices_ctdc.yml")) {
            assertNotNull(inputStream, "Index configuration should be present");
            yamlConfig = yaml.load(inputStream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load index configuration", e);
        }

        List<Map<String, Object>> indices = (List<Map<String, Object>>) yamlConfig.get("Indices");
        assertNotNull(indices, "Indices should be present in the YAML configuration");

        assertIndexPropagatesSurvivalStatus(indices, "tab_biospecimens");
        assertIndexPropagatesSurvivalStatus(indices, "tab_data_files");
        assertIndexPropagatesSurvivalStatus(indices, "cart_data_files");
        assertIndexPropagatesSurvivalStatus(indices, "biospecimen_data_file");
        assertIndexPropagatesSurvivalStatus(indices, "study_file_overview");
        assertIndexPropagatesSurvivalStatus(indices, "widgets_facets_counts");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void facetSearchConfigurationExposesSurvivalStatusFacet() {
        Map<String, Object> yamlConfig;
        Yaml yaml = new Yaml();
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("yaml/facet_search_es.yml")) {
            assertNotNull(inputStream, "Facet configuration should be present");
            yamlConfig = yaml.load(inputStream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load facet configuration", e);
        }

        List<Map<String, Object>> queries = (List<Map<String, Object>>) yamlConfig.get("queries");
        assertNotNull(queries, "Queries should be present in the facet YAML configuration");
        Map<String, Object> searchParticipants = queries.stream()
                .filter(query -> "searchParticipants".equals(query.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("searchParticipants query is missing"));

        List<Map<String, Object>> returnFields = (List<Map<String, Object>>) searchParticipants.get("returnFields");
        assertNotNull(returnFields, "searchParticipants return fields should be present");

        assertFacetFieldExists(returnFields, "participantCountBySurvivalStatus");
        assertFacetFieldExists(returnFields, "filterParticipantCountBySurvivalStatus");
    }

    private static void assertTypeExposesField(TypeDefinitionRegistry registry, String typeName, String fieldName) {
        Optional<ObjectTypeDefinition> type = registry.getType(typeName, ObjectTypeDefinition.class);
        assertTrue(type.isPresent(), typeName + " type should exist");
        assertTrue(
                type.get().getFieldDefinitions().stream().anyMatch(fieldDefinition -> fieldName.equals(fieldDefinition.getName())),
                typeName + " should expose " + fieldName
        );
    }

    private static void assertQueryArgumentExists(ObjectTypeDefinition queryType, String queryName, String argumentName) {
        FieldDefinition queryField = queryType.getFieldDefinitions().stream()
                .filter(fieldDefinition -> queryName.equals(fieldDefinition.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(queryName + " query should exist"));

        assertTrue(
                queryField.getInputValueDefinitions().stream().anyMatch(argument -> argumentName.equals(argument.getName())),
                queryName + " should accept " + argumentName
        );
    }

    @SuppressWarnings("unchecked")
    private static void assertIndexPropagatesSurvivalStatus(List<Map<String, Object>> indices, String indexName) {
        Map<String, Object> index = indices.stream()
                .filter(candidate -> indexName.equals(candidate.get("index_name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(indexName + " index is missing"));

        Map<String, Object> mapping = (Map<String, Object>) index.get("mapping");
        assertTrue(mapping.containsKey("survival_status"), indexName + " mapping should contain survival_status");
        assertEquals("keyword", ((Map<String, Object>) mapping.get("survival_status")).get("type"));

        String cypherQuery = (String) index.get("cypher_query");
        assertTrue(cypherQuery.contains("participant_status"), indexName + " cypher should pull participant_status nodes");
        assertTrue(cypherQuery.contains("survival_status"), indexName + " cypher should return survival_status");
    }

    @SuppressWarnings("unchecked")
    private static void assertFacetFieldExists(List<Map<String, Object>> returnFields, String fieldName) {
        Map<String, Object> field = returnFields.stream()
                .filter(candidate -> fieldName.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fieldName + " facet field should exist"));

        Map<String, Object> filter = (Map<String, Object>) field.get("filter");
        assertEquals("survival_status", filter.get("selectedField"));

        List<String> index = (List<String>) field.get("index");
        assertEquals(List.of("widgets_facets_counts"), index);
    }
}