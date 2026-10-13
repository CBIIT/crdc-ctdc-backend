package gov.nih.nci.bento;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.TypeName;
import graphql.language.VariableReference;
import graphql.parser.Parser;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.RuntimeWiring;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProgramSearchConfigurationTest {

    private static final List<String> PROGRAM_FIELDS = List.of("program_name", "program_short_name");

    @Test
    public void suppliedSearchOperationIsValidAgainstPrivateSchema() throws Exception {
        TypeDefinitionRegistry registry;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input);
            registry = new SchemaParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        String operation = Files.readString(Path.of("graphql/operations/search.graphql"));
        OperationDefinition search = (OperationDefinition) new Parser().parseDocument(operation)
                .getDefinitions().get(0);
        for (String programField : PROGRAM_FIELDS) {
            assertTrue(search.getVariableDefinitions().stream()
                    .anyMatch(variable -> programField.equals(variable.getName())),
                    "search must declare $" + programField);
        }
        List<Field> calls = search.getSelectionSet().getSelections().stream()
                .map(selection -> (Field) selection).toList();
        assertEquals(3, calls.size(), "search must retain the two tab counts and the main result");
        for (Field call : calls) {
            assertEquals("searchParticipants", call.getName());
            for (String programField : PROGRAM_FIELDS) {
                assertTrue(call.getArguments().stream().anyMatch(argument ->
                        programField.equals(argument.getName())
                                && argument.getValue() instanceof VariableReference reference
                                && programField.equals(reference.getName())),
                        "every searchParticipants call must forward $" + programField);
            }
        }
        Field main = calls.stream().filter(call -> call.getAlias() == null).findFirst().orElseThrow();
        List<String> resultFields = main.getSelectionSet().getSelections().stream()
                .map(selection -> ((Field) selection).getName()).toList();
        for (String field : List.of("numberOfPrograms", "participantCountByProgramName",
                "filterParticipantCountByProgramName", "participantCountByProgramShortName",
                "filterParticipantCountByProgramShortName")) {
            assertTrue(resultFields.contains(field), "search must select " + field);
        }
        GraphQL graphQL = GraphQL.newGraphQL(
                new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build())
        ).build();
        var result = graphQL.execute(ExecutionInput.newExecutionInput().query(operation).build());
        assertTrue(result.getErrors().isEmpty(), () -> "Client operation has GraphQL errors: " + result.getErrors());
    }

    @Test
    public void suppliedBiospecimenSearchOperationIsValidAgainstPrivateSchema() throws Exception {
        TypeDefinitionRegistry registry;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input);
            registry = new SchemaParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        String operation = Files.readString(Path.of("graphql/operations/search-biospecimen-filters.graphql"));
        GraphQL graphQL = GraphQL.newGraphQL(
                new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build())
        ).build();
        var result = graphQL.execute(ExecutionInput.newExecutionInput().query(operation).build());
        assertTrue(result.getErrors().isEmpty(), () -> "Client operation has GraphQL errors: " + result.getErrors());

        OperationDefinition search = (OperationDefinition) new Parser().parseDocument(operation)
                .getDefinitions().get(0);
        Field call = (Field) search.getSelectionSet().getSelections().get(0);
        for (String field : PROGRAM_FIELDS) {
            assertTrue(search.getVariableDefinitions().stream().anyMatch(variable -> field.equals(variable.getName())),
                    "search must declare $" + field);
            assertTrue(call.getArguments().stream().anyMatch(argument ->
                            field.equals(argument.getName())
                                    && argument.getValue() instanceof VariableReference reference
                                    && field.equals(reference.getName())),
                    "searchParticipants must forward $" + field);
        }
        List<String> resultFields = call.getSelectionSet().getSelections().stream()
                .map(selection -> ((Field) selection).getName()).toList();
        for (String field : List.of("numberOfPrograms", "participantCountByProgramName",
                "filterParticipantCountByProgramName", "participantCountByProgramShortName",
                "filterParticipantCountByProgramShortName")) {
            assertTrue(resultFields.contains(field), "search must select " + field);
        }
    }

    @Test
    public void programFieldsAreReturnedAndFilterable() {
        TypeDefinitionRegistry registry;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input);
            registry = new SchemaParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("Unable to load private GraphQL schema", e);
        }

        for (String typeName : List.of("ParticipantOverview", "BiospecimenOverview", "FileOverview",
                "FileParticipant", "StudyFileOverviewInfo", "GS_participant", "GS_Biospecimen")) {
            ObjectTypeDefinition type = registry.getType(typeName, ObjectTypeDefinition.class)
                    .orElseThrow(() -> new AssertionError(typeName + " is missing"));
            for (String name : PROGRAM_FIELDS) {
                FieldDefinition field = type.getFieldDefinitions().stream()
                        .filter(definition -> name.equals(definition.getName()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(typeName + " is missing " + name));
                assertInstanceOf(ListType.class, field.getType(), typeName + "." + name);
            }
        }

        ObjectTypeDefinition queryType = registry.getType("QueryType", ObjectTypeDefinition.class).orElseThrow();
        for (String queryName : List.of("participantOverview", "biospecimenOverview", "fileOverview",
                "participant_data_files", "biospecimen_data_files", "studyFileOverview",
                "StudyFileOverviewByStudyShortName", "filesInList", "fileIDsFromList", "searchParticipants")) {
            FieldDefinition query = queryType.getFieldDefinitions().stream()
                    .filter(definition -> queryName.equals(definition.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(queryName + " is missing"));
            for (String name : PROGRAM_FIELDS) {
                assertTrue(query.getInputValueDefinitions().stream()
                        .anyMatch(argument -> name.equals(argument.getName()) && argument.getType() instanceof ListType),
                        queryName + " must filter on " + name);
            }
        }

        ObjectTypeDefinition searchResult = registry.getType("SearchResult", ObjectTypeDefinition.class).orElseThrow();
        FieldDefinition numberOfPrograms = searchResult.getFieldDefinitions().stream()
                .filter(field -> "numberOfPrograms".equals(field.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("numberOfPrograms is missing"));
        assertEquals("Int", ((TypeName) numberOfPrograms.getType()).getName());
        for (String name : List.of("participantCountByProgramName", "filterParticipantCountByProgramName",
                "participantCountByProgramShortName", "filterParticipantCountByProgramShortName")) {
            assertTrue(searchResult.getFieldDefinitions().stream().anyMatch(field -> name.equals(field.getName())),
                    name + " must be exposed");
        }
        for (String name : List.of("studyFileOverviewCountByDataFileType",
                "studyFileOverviewCountByDataFileFormat", "filterStudyFileOverviewCountByDataFileType",
                "filterStudyFileOverviewCountByDataFileFormat")) {
            FieldDefinition facet = searchResult.getFieldDefinitions().stream()
                    .filter(field -> name.equals(field.getName())).findFirst().orElseThrow();
            for (String programField : PROGRAM_FIELDS) {
                assertTrue(facet.getInputValueDefinitions().stream()
                        .anyMatch(argument -> programField.equals(argument.getName())),
                        name + " must filter on " + programField);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allProgramIndicesExposeTheIndexedFields() {
        Map<String, Object> config;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("yaml/es_indices_ctdc.yml")) {
            assertNotNull(input);
            config = new Yaml().load(input);
        } catch (Exception e) {
            throw new AssertionError("Unable to load index configuration", e);
        }
        List<Map<String, Object>> indices = (List<Map<String, Object>>) config.get("Indices");
        for (String name : List.of("widgets_facets_counts", "tab_participants", "tab_biospecimens",
                "tab_data_files", "cart_data_files", "biospecimen_data_file",
                "study_file_overview", "therapy_count")) {
            Map<String, Object> index = indices.stream()
                    .filter(entry -> name.equals(entry.get("index_name")))
                    .findFirst().orElseThrow(() -> new AssertionError(name + " is missing"));
            Map<String, Object> mapping = (Map<String, Object>) index.get("mapping");
            String cypher = (String) index.get("cypher_query");
            for (String field : PROGRAM_FIELDS) {
                assertEquals("keyword", ((Map<String, Object>) mapping.get(field)).get("type"),
                        name + "." + field);
                assertTrue(cypher.contains("AS " + field) || cypher.contains("as " + field),
                        name + " must return " + field);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void globalSearchMatchesProgramFieldsInBothResultAndCountQueries() {
        Map<String, Object> config;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("yaml/global_search_es.yml")) {
            assertNotNull(input);
            config = new Yaml().load(input);
        } catch (Exception e) {
            throw new AssertionError("Unable to load global search configuration", e);
        }
        Map<String, Object> globalSearch = ((List<Map<String, Object>>) config.get("queries")).stream()
                .filter(query -> "globalSearch".equals(query.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> results = (List<Map<String, Object>>) globalSearch.get("returnFields");
        for (String name : List.of("participants", "participant_count", "biospecimens", "biospecimen_count")) {
            Map<String, Object> result = results.stream()
                    .filter(entry -> name.equals(entry.get("name"))).findFirst().orElseThrow();
            Map<String, Object> filter = (Map<String, Object>) result.get("filter");
            List<Map<String, Object>> searches = (List<Map<String, Object>>) filter.get("searches");
            for (String field : PROGRAM_FIELDS) {
                assertTrue(searches.stream().anyMatch(search -> field.equals(search.get("field"))),
                        name + " must search " + field);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void programFacetsUseIndexedFields() {
        Map<String, Object> config;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("yaml/facet_search_es.yml")) {
            assertNotNull(input);
            config = new Yaml().load(input);
        } catch (Exception e) {
            throw new AssertionError("Unable to load facet search configuration", e);
        }

        List<Map<String, Object>> queries = (List<Map<String, Object>>) config.get("queries");
        Map<String, Object> search = queries.stream()
                .filter(query -> "searchParticipants".equals(query.get("name")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> facets = (List<Map<String, Object>>) search.get("returnFields");
        Map<String, Object> programCount = facets.stream()
                .filter(entry -> "numberOfPrograms".equals(entry.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("numberOfPrograms is missing"));
        assertEquals(List.of("widgets_facets_counts"), programCount.get("index"));
        Map<String, Object> programCountFilter = (Map<String, Object>) programCount.get("filter");
        assertEquals("aggregation", programCountFilter.get("type"));
        assertEquals("program_short_name", programCountFilter.get("selectedField"));
        Map<String, Object> programCountResult = (Map<String, Object>) programCount.get("result");
        assertEquals("int", programCountResult.get("type"));
        assertEquals("count_bucket_keys", programCountResult.get("method"));
        for (String field : PROGRAM_FIELDS) {
            String suffix = field.equals("program_name") ? "ProgramName" : "ProgramShortName";
            for (boolean filtered : List.of(false, true)) {
                String name = (filtered ? "filterParticipantCountBy" : "participantCountBy") + suffix;
                Map<String, Object> facet = facets.stream().filter(entry -> name.equals(entry.get("name")))
                        .findFirst().orElseThrow(() -> new AssertionError(name + " is missing"));
                assertEquals(List.of("widgets_facets_counts"), facet.get("index"));
                Map<String, Object> filter = (Map<String, Object>) facet.get("filter");
                assertEquals(field, filter.get("selectedField"));
                assertEquals(filtered, Boolean.TRUE.equals(filter.get("ignoreSelectedField")));
            }
        }
    }
}
