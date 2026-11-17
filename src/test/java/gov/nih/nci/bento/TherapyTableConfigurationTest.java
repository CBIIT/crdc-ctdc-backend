package gov.nih.nci.bento;

import gov.nih.nci.bento.model.search.MultipleRequests;
import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.TermsQueryBuilder;
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TherapyTableConfigurationTest {

    private static final Set<String> PAGINATION_ARGS = Set.of("order_by", "sort_direction", "first", "offset");
    private static final Set<String> NESTED_FIELDS = Set.of("biospecimen_info", "file_info", "data_files");
    private static final List<String> SCALAR_THERAPY_FIELDS = List.of(
            "participant_id", "course_number", "therapy_name", "therapy_name_key", "therapy_string",
            "therapy_category", "therapy_dose", "therapy_dose_units", "therapy_frequency",
            "current_response_to_therapy", "best_response_to_therapy", "survival_status");
    private static final List<String> LIST_THERAPY_FIELDS = List.of(
            "therapy_type", "number_of_doses", "planned_dose", "therapy_record_id",
            "study_id", "race", "specimen_type", "anatomical_collection_site");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> index(String name) {
        try (InputStream input = ClassLoader.getSystemResourceAsStream("yaml/es_indices_ctdc.yml")) {
            assertNotNull(input);
            Map<String, Object> config = new Yaml().load(input);
            return ((List<Map<String, Object>>) config.get("Indices")).stream()
                    .filter(entry -> name.equals(entry.get("index_name")))
                    .findFirst().orElseThrow(() -> new AssertionError(name + " index is missing"));
        } catch (Exception e) {
            throw new AssertionError("Unable to load index configuration", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queries(String file) {
        try (InputStream input = ClassLoader.getSystemResourceAsStream(file)) {
            assertNotNull(input);
            Map<String, Object> config = new Yaml().load(input);
            return (List<Map<String, Object>>) config.get("queries");
        } catch (Exception e) {
            throw new AssertionError("Unable to load " + file, e);
        }
    }

    private static TypeDefinitionRegistry schema() {
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input);
            return new SchemaParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("Unable to load private GraphQL schema", e);
        }
    }

    private static FieldDefinition field(ObjectTypeDefinition type, String name) {
        return type.getFieldDefinitions().stream().filter(f -> name.equals(f.getName())).findFirst()
                .orElseThrow(() -> new AssertionError(type.getName() + "." + name + " is missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void therapyFileIndexMapsEveryTherapyTableFieldWithTheSameType() {
        Map<String, Object> tableMapping = (Map<String, Object>) index("tab_therapies").get("mapping");
        Map<String, Object> fileMapping = (Map<String, Object>) index("therapy_data_file").get("mapping");
        tableMapping.forEach((name, definition) -> {
            assertTrue(fileMapping.containsKey(name), "therapy_data_file must map " + name);
            assertEquals(((Map<String, Object>) definition).get("type"),
                    ((Map<String, Object>) fileMapping.get(name)).get("type"), name);
        });
        for (String name : NESTED_FIELDS) {
            assertEquals("nested", ((Map<String, Object>) fileMapping.get(name)).get("type"), name);
        }
        for (String name : List.of("data_file_name", "data_file_description", "drs_uri", "association")) {
            assertEquals("keyword", ((Map<String, Object>) fileMapping.get(name)).get("type"), name);
        }

        for (String indexName : List.of("tab_therapies", "therapy_data_file")) {
            Map<String, Object> mapping = (Map<String, Object>) index(indexName).get("mapping");
            String cypher = (String) index(indexName).get("cypher_query");
            for (String name : mapping.keySet()) {
                assertTrue(cypher.matches("(?s).*(AS " + name + "\\b|[\\s,]" + name + ",).*"),
                        indexName + " cypher must return " + name);
            }
        }
    }

    @Test
    public void therapyTableQueriesUseTheTherapyIndices() {
        List<Map<String, Object>> queries = queries("yaml/single_search_es.yml");
        assertPaginationQuery(queries, "therapyOverview", "tab_therapies", "participant_id");
        assertPaginationQuery(queries, "therapy_data_files", "therapy_data_file", "data_file_name");
    }

    @SuppressWarnings("unchecked")
    private static void assertPaginationQuery(List<Map<String, Object>> queries, String name, String index, String sort) {
        Map<String, Object> query = queries.stream().filter(q -> name.equals(q.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError(name + " query is missing"));
        assertEquals(List.of(index), query.get("index"));
        Map<String, Object> filter = (Map<String, Object>) query.get("filter");
        assertEquals("pagination", filter.get("type"));
        assertEquals(sort, filter.get("defaultSortField"));
        assertEquals("object_array", ((Map<String, Object>) query.get("result")).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void therapyFacetsAndCountUseTheTherapyTableIndex() {
        Map<String, Object> searchParticipants = queries("yaml/facet_search_es.yml").stream()
                .filter(q -> "searchParticipants".equals(q.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> fields = (List<Map<String, Object>>) searchParticipants.get("returnFields");

        ObjectTypeDefinition searchResult = schema().getType("SearchResult", ObjectTypeDefinition.class).orElseThrow();
        Map<String, String> counts = Map.of(
                "numberOfTherapies", "therapy_count",
                "numberOfTherapyByDose", "tab_therapies");
        counts.forEach((name, index) -> {
            Map<String, Object> count = fields.stream().filter(f -> name.equals(f.get("name")))
                    .findFirst().orElseThrow(() -> new AssertionError(name + " is missing"));
            assertEquals(List.of(index), count.get("index"), name);
            assertEquals("therapy_name", ((Map<String, Object>) count.get("filter")).get("selectedField"), name);
            Map<String, Object> result = (Map<String, Object>) count.get("result");
            assertEquals("int", result.get("type"), name);
            assertEquals("count_bucket_keys", result.get("method"), name);
            assertEquals("Int", ((TypeName) field(searchResult, name).getType()).getName(), name);
        });

        Map<String, String> facets = Map.of(
                "therapyCountByTherapyDoseUnits", "therapy_dose_units",
                "therapyCountByTherapyFrequency", "therapy_frequency");
        facets.forEach((name, selectedField) -> {
            for (String facetName : List.of(name, "filter" + Character.toUpperCase(name.charAt(0)) + name.substring(1))) {
                Map<String, Object> facet = fields.stream().filter(f -> facetName.equals(f.get("name")))
                        .findFirst().orElseThrow(() -> new AssertionError(facetName + " is missing"));
                assertEquals(List.of("tab_therapies"), facet.get("index"));
                Map<String, Object> filter = (Map<String, Object>) facet.get("filter");
                assertEquals(selectedField, filter.get("selectedField"));
                assertEquals(facetName.startsWith("filter"), Boolean.TRUE.equals(filter.get("ignoreSelectedField")),
                        facetName + " ignoreSelectedField");
                assertEquals("group_count", ((Map<String, Object>) facet.get("result")).get("type"));
                FieldDefinition definition = field(searchResult, facetName);
                assertEquals("GroupCount", ((TypeName) ((ListType) definition.getType()).getType()).getName());
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void currentResponseFacetMatchesBestResponseAndExcludesItsOwnSelection() throws Exception {
        Map<String, Object> widgetIndex = index("widgets_facets_counts");
        Map<String, Object> mapping = (Map<String, Object>) widgetIndex.get("mapping");
        String cypher = (String) widgetIndex.get("cypher_query");
        for (String field : List.of("current_response_to_therapy", "best_response_to_therapy")) {
            assertEquals("keyword", ((Map<String, Object>) mapping.get(field)).get("type"), field);
            assertTrue(cypher.contains("tt." + field), field + " must be collected");
            assertTrue(cypher.contains(" AS " + field), field + " must be returned");
        }

        ESService esService = mock(ESService.class);
        AtomicReference<List<MultipleRequests>> requests = new AtomicReference<>();
        List<Map<String, Object>> current = List.of(Map.of("group", "Complete Response", "subjects", 2));
        List<Map<String, Object>> best = List.of(Map.of("group", "Partial Response", "subjects", 2));
        List<Map<String, Object>> available = List.of(
                Map.of("group", "Complete Response", "subjects", 2),
                Map.of("group", "Partial Response", "subjects", 1));
        List<Map<String, Object>> bestAvailable = List.of(
                Map.of("group", "Partial Response", "subjects", 2),
                Map.of("group", "Stable Disease", "subjects", 1));
        when(esService.<Object>elasticMultiSend(anyList())).thenAnswer(invocation -> {
            requests.set(invocation.getArgument(0));
            return Map.of(
                    "participantCountByCurrentResponseToTherapy", current,
                    "filterParticipantCountByCurrentResponseToTherapy", available,
                    "participantCountByBestResponseToTherapy", best,
                    "filterParticipantCountByBestResponseToTherapy", bestAvailable);
        });

        GraphQL graphQL = GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(
                schema(), new PrivateESDataFetcher(esService).buildRuntimeWiring())).build();
        var result = graphQL.execute("""
                {
                  searchParticipants(
                    sex: ["Female"]
                    current_response_to_therapy: ["Complete Response", "Stable Disease"]
                    best_response_to_therapy: ["Partial Response"]
                  ) {
                    participantCountByCurrentResponseToTherapy { group subjects }
                    filterParticipantCountByCurrentResponseToTherapy { group subjects }
                    participantCountByBestResponseToTherapy { group subjects }
                    filterParticipantCountByBestResponseToTherapy { group subjects }
                  }
                }
                """);

        assertTrue(result.getErrors().isEmpty(), () -> result.getErrors().toString());
        Map<?, ?> data = result.getData();
        Map<?, ?> search = assertInstanceOf(Map.class, data.get("searchParticipants"));
        assertEquals(current, search.get("participantCountByCurrentResponseToTherapy"));
        assertEquals(available, search.get("filterParticipantCountByCurrentResponseToTherapy"));
        assertEquals(best, search.get("participantCountByBestResponseToTherapy"));
        assertEquals(bestAvailable, search.get("filterParticipantCountByBestResponseToTherapy"));

        assertNotNull(requests.get());
        Map<String, List<String>> allFilters = Map.of(
                "sex", List.of("Female"),
                "current_response_to_therapy", List.of("Complete Response", "Stable Disease"),
                "best_response_to_therapy", List.of("Partial Response"));
        assertResponseFacetRequest(requests.get(), "participantCountByCurrentResponseToTherapy",
                "current_response_to_therapy", allFilters);
        assertResponseFacetRequest(requests.get(), "filterParticipantCountByCurrentResponseToTherapy",
                "current_response_to_therapy", Map.of(
                        "sex", List.of("Female"),
                        "best_response_to_therapy", List.of("Partial Response")));
        assertResponseFacetRequest(requests.get(), "participantCountByBestResponseToTherapy",
                "best_response_to_therapy", allFilters);
        assertResponseFacetRequest(requests.get(), "filterParticipantCountByBestResponseToTherapy",
                "best_response_to_therapy", Map.of(
                        "sex", List.of("Female"),
                        "current_response_to_therapy", List.of("Complete Response", "Stable Disease")));
    }

    private static void assertResponseFacetRequest(List<MultipleRequests> requests, String name,
                                                   String field, Map<String, List<String>> expectedFilters) {
        MultipleRequests request = requests.stream().filter(candidate -> name.equals(candidate.getName()))
                .findFirst().orElseThrow(() -> new AssertionError(name + " is missing"));
        assertArrayEquals(new String[]{"widgets_facets_counts"}, request.getRequest().indices(), name);
        var source = request.getRequest().source();
        assertEquals(0, source.size(), name);
        assertEquals(1, source.aggregations().getAggregatorFactories().size(), name);
        TermsAggregationBuilder aggregation = assertInstanceOf(TermsAggregationBuilder.class,
                source.aggregations().getAggregatorFactories().iterator().next());
        assertEquals(field, aggregation.field(), name);
        BoolQueryBuilder query = assertInstanceOf(BoolQueryBuilder.class, source.query());
        assertEquals(expectedFilters.size(), query.filter().size(), name);
        Map<String, List<Object>> filters = new HashMap<>();
        query.filter().forEach(filter -> {
            TermsQueryBuilder terms = assertInstanceOf(TermsQueryBuilder.class, filter);
            filters.put(terms.fieldName(), terms.values());
        });
        assertEquals(expectedFilters, filters, name);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void schemaTypesAndArgumentsMatchTheIndexedFields() {
        TypeDefinitionRegistry registry = schema();
        ObjectTypeDefinition queryType = registry.getType("QueryType", ObjectTypeDefinition.class).orElseThrow();

        Map<String, String[]> queries = Map.of(
                "therapyOverview", new String[]{"TherapyOverview", "tab_therapies"},
                "therapy_data_files", new String[]{"TherapyDataFile", "therapy_data_file"});
        queries.forEach((queryName, target) -> {
            FieldDefinition query = field(queryType, queryName);
            assertEquals(target[0], ((TypeName) ((ListType) query.getType()).getType()).getName());
            Map<String, Object> mapping = (Map<String, Object>) index(target[1]).get("mapping");
            for (InputValueDefinition argument : query.getInputValueDefinitions()) {
                if (PAGINATION_ARGS.contains(argument.getName())) continue;
                assertTrue(mapping.containsKey(argument.getName()),
                        queryName + "." + argument.getName() + " must filter an indexed field of " + target[1]);
                assertInstanceOf(ListType.class, argument.getType(), queryName + "." + argument.getName());
            }

            ObjectTypeDefinition type = registry.getType(target[0], ObjectTypeDefinition.class).orElseThrow();
            for (FieldDefinition definition : type.getFieldDefinitions()) {
                assertTrue(mapping.containsKey(definition.getName()),
                        target[0] + "." + definition.getName() + " must be indexed in " + target[1]);
            }
            for (String name : SCALAR_THERAPY_FIELDS) {
                assertInstanceOf(TypeName.class, field(type, name).getType(), target[0] + "." + name);
            }
            for (String name : LIST_THERAPY_FIELDS) {
                assertInstanceOf(ListType.class, field(type, name).getType(), target[0] + "." + name);
            }
            assertEquals("Int", ((TypeName) field(type, "dose_records").getType()).getName());
        });

        ObjectTypeDefinition overview = registry.getType("TherapyOverview", ObjectTypeDefinition.class).orElseThrow();
        assertInstanceOf(ListType.class, field(overview, "data_file_uuid").getType());
        ObjectTypeDefinition dataFile = registry.getType("TherapyDataFile", ObjectTypeDefinition.class).orElseThrow();
        for (String name : List.of("study_short_name", "program_name", "program_short_name", "ctep_disease_term")) {
            assertEquals("String", assertInstanceOf(TypeName.class, field(overview, name).getType()).getName(),
                    "TherapyOverview." + name);
            assertEquals("String", assertInstanceOf(TypeName.class,
                    assertInstanceOf(ListType.class, field(dataFile, name).getType()).getType()).getName(),
                    "TherapyDataFile." + name);
        }
        assertEquals("String", ((TypeName) field(dataFile, "data_file_uuid").getType()).getName());
        assertEquals("Float", ((TypeName) field(dataFile, "data_file_size").getType()).getName());
        assertInstanceOf(ListType.class, field(dataFile, "association").getType());
    }

    @Test
    public void therapyOperationsAreValidAgainstPrivateSchema() {
        String operation = """
                query therapyTables($therapy_name: [String], $therapy_dose_units: [String]) {
                  therapyOverview(therapy_name: $therapy_name, therapy_dose_units: $therapy_dose_units) {
                    participant_id course_number therapy_name dose_records
                    study_short_name program_short_name data_file_uuid
                    biospecimen_info { specimen_id specimen_category }
                    data_files { data_file_uuid data_file_name }
                  }
                  therapy_data_files(therapy_name: $therapy_name, association: ["participant"]) {
                    data_file_uuid data_file_name data_file_size drs_uri association therapy_name
                    file_info { data_file_uuid data_file_type }
                  }
                  searchParticipants(therapy_name: $therapy_name) {
                    numberOfTherapies
                    numberOfTherapyByDose
                    therapyCountByTherapyDoseUnits { group subjects }
                    filterTherapyCountByTherapyFrequency { group subjects }
                  }
                }
                """;
        GraphQL graphQL = GraphQL.newGraphQL(new SchemaGenerator()
                .makeExecutableSchema(schema(), RuntimeWiring.newRuntimeWiring().build())).build();
        var result = graphQL.execute(ExecutionInput.newExecutionInput().query(operation).build());
        assertTrue(result.getErrors().isEmpty(), () -> "Therapy operation has GraphQL errors: " + result.getErrors());
    }
}
