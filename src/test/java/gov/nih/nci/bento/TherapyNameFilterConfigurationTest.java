package gov.nih.nci.bento;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TherapyNameFilterConfigurationTest {

    @Test
    public void therapyFilteredQueriesUseTherapyNameArgument() throws Exception {
        String schema;
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(inputStream, "GraphQL schema should be present");
            schema = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);
        Optional<ObjectTypeDefinition> queryType = registry.getType("QueryType", ObjectTypeDefinition.class);
        assertTrue(queryType.isPresent(), "QueryType should exist");

        assertQueryUsesTherapyNameFilter(queryType.get(), "participantOverview");
        assertQueryUsesTherapyNameFilter(queryType.get(), "biospecimenOverview");
        assertQueryUsesTherapyNameFilter(queryType.get(), "fileOverview");
        assertQueryUsesTherapyNameFilter(queryType.get(), "participant_data_files");
        assertQueryUsesTherapyNameFilter(queryType.get(), "biospecimen_data_files");
        assertQueryUsesTherapyNameFilter(queryType.get(), "searchParticipants");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void therapyFilteredIndicesExposeTherapyName() {
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

        assertIndexPropagatesTherapyName(indices, "widgets_facets_counts", "AS therapy_name");
        assertIndexPropagatesTherapyName(indices, "tab_participants", "AS therapy_name");
        assertIndexPropagatesTherapyName(indices, "tab_biospecimens", "AS therapy_name");
        assertIndexPropagatesTherapyName(indices, "tab_data_files", "AS therapy_name");
        assertIndexPropagatesTherapyName(indices, "biospecimen_data_file", "AS therapy_name");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void therapyFacetAggregationsUseTherapyName() {
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

        assertFacetUsesTherapyName(returnFields, "participantCountByTherapy");
        assertFacetUsesTherapyName(returnFields, "filterParticipantCountByTherapy");
    }

    private static void assertQueryUsesTherapyNameFilter(ObjectTypeDefinition queryType, String queryName) {
        FieldDefinition queryField = queryType.getFieldDefinitions().stream()
                .filter(fieldDefinition -> queryName.equals(fieldDefinition.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(queryName + " query should exist"));

        assertTrue(
                queryField.getInputValueDefinitions().stream().anyMatch(argument -> "therapy_name".equals(argument.getName())),
                queryName + " should accept therapy_name"
        );
        assertFalse(
                queryField.getInputValueDefinitions().stream().anyMatch(argument -> "therapy".equals(argument.getName())),
                queryName + " should not expose therapy as a filter argument"
        );
    }

    @SuppressWarnings("unchecked")
    private static void assertIndexPropagatesTherapyName(List<Map<String, Object>> indices, String indexName, String cypherFragment) {
        Map<String, Object> index = indices.stream()
                .filter(candidate -> indexName.equals(candidate.get("index_name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(indexName + " index is missing"));

        Map<String, Object> mapping = (Map<String, Object>) index.get("mapping");
        assertTrue(mapping.containsKey("therapy_name"), indexName + " mapping should contain therapy_name");
        assertEquals("keyword", ((Map<String, Object>) mapping.get("therapy_name")).get("type"));

        String cypherQuery = (String) index.get("cypher_query");
        assertTrue(cypherQuery.contains(cypherFragment), indexName + " cypher should return therapy_name");
    }

    @SuppressWarnings("unchecked")
    private static void assertFacetUsesTherapyName(List<Map<String, Object>> returnFields, String fieldName) {
        Map<String, Object> field = returnFields.stream()
                .filter(candidate -> fieldName.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fieldName + " facet field should exist"));

        Map<String, Object> filter = (Map<String, Object>) field.get("filter");
        assertEquals("therapy_name", filter.get("selectedField"));
    }
}
