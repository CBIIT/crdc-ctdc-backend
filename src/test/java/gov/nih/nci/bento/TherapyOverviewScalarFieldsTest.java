package gov.nih.nci.bento;

import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.TermsQueryBuilder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TherapyOverviewScalarFieldsTest {

    private GraphQL graphQL(ESService esService) throws Exception {
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input);
            var registry = new SchemaParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            var wiring = new PrivateESDataFetcher(esService).buildRuntimeWiring();
            return GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(registry, wiring)).build();
        }
    }

    private void verifyFilters(ESService esService, String queryName,
                               Map<String, List<String>> expected) throws Exception {
        verify(esService).elasticMultiSend(argThat(requests -> {
            if (requests.size() != 1 || !queryName.equals(requests.get(0).getName())) return false;
            QueryBuilder query = requests.get(0).getRequest().source().query();
            if (!(query instanceof BoolQueryBuilder bool)) return false;
            Map<String, List<Object>> filters = new HashMap<>();
            for (QueryBuilder filter : bool.filter()) {
                if (!(filter instanceof TermsQueryBuilder terms)) return false;
                filters.put(terms.fieldName(), terms.values());
            }
            return filters.equals(expected);
        }));
    }

    @Test
    public void therapyOverviewProgramFieldsRemainListsAndFileFiltersArePreserved() throws Exception {
        Map<String, Object> indexedRow = Map.of(
                "study_short_name", List.of("CMB"),
                "program_name", List.of("Not Applicable"),
                "program_short_name", List.of("NA"),
                "ctep_disease_term", List.of("Plasma Cell Myeloma"));
        ESService esService = mock(ESService.class);
        when(esService.<Object>elasticMultiSend(anyList())).thenReturn(Map.of(
                "therapyOverview", List.of(indexedRow),
                "therapy_data_files", List.of(indexedRow)));

        ExecutionResult result = graphQL(esService).execute("""
                {
                  therapyOverview(
                    study_short_name: ["CMB"]
                    program_name: ["Not Applicable"]
                    program_short_name: ["NA"]
                    ctep_disease_term: ["Plasma Cell Myeloma"]
                  ) {
                    study_short_name
                    display_program: program_name
                    program_short_name
                    ctep_disease_term
                  }
                  therapy_data_files(
                    participant_id: ["CTDC-TEST-1"]
                    study_short_name: ["CMB"]
                    program_short_name: ["NA"]
                  ) {
                    display_study: study_short_name
                    program_name
                    display_short_program: program_short_name
                    ctep_disease_term
                  }
                }
                """);

        assertTrue(result.getErrors().isEmpty(), () -> result.getErrors().toString());
        Map<?, ?> data = result.getData();
        List<?> overview = assertInstanceOf(List.class, data.get("therapyOverview"));
        assertEquals(Map.of(
                "study_short_name", List.of("CMB"),
                "display_program", List.of("Not Applicable"),
                "program_short_name", List.of("NA"),
                "ctep_disease_term", "Plasma Cell Myeloma"), overview.get(0));
        List<?> files = assertInstanceOf(List.class, data.get("therapy_data_files"));
        assertEquals(Map.of(
                "display_study", "CMB",
                "program_name", List.of("Not Applicable"),
                "display_short_program", "NA",
                "ctep_disease_term", List.of("Plasma Cell Myeloma")), files.get(0));

        verifyFilters(esService, "therapyOverview", Map.of(
                "study_short_name", List.of("CMB"),
                "program_name", List.of("Not Applicable"),
                "program_short_name", List.of("NA"),
                "ctep_disease_term", List.of("Plasma Cell Myeloma")));
        verifyFilters(esService, "therapy_data_files", Map.of(
                "participant_id", List.of("CTDC-TEST-1"),
                "study_short_name", List.of("CMB"),
                "program_short_name", List.of("NA")));
    }

    @Test
    public void therapyDisplaysHandleScalarMissingAndMultipleIndexedValues() throws Exception {
        Map<String, Object> firstRow = new HashMap<>();
        firstRow.put("study_short_name", List.of("Zulu", "Alpha"));
        firstRow.put("program_name", List.of("Already scalar"));
        firstRow.put("program_short_name", List.of());
        firstRow.put("ctep_disease_term", Arrays.asList(null, "One"));
        Map<String, Object> secondRow = Map.of(
                "study_short_name", List.of("Alpha", "Zulu"),
                "ctep_disease_term", List.of());
        Map<String, Object> thirdRow = Map.of(
                "study_short_name", List.of("Single"),
                "program_short_name", Arrays.asList(null, "NA", "NA"));
        ESService esService = mock(ESService.class);
        when(esService.<Object>elasticMultiSend(anyList())).thenReturn(Map.of(
                "therapyOverview", List.of(firstRow, secondRow, thirdRow),
                "therapy_data_files", List.of(firstRow, secondRow, Map.of(
                        "study_short_name", "Single",
                        "program_short_name", Arrays.asList(null, "NA", "NA")))));

        ExecutionResult result = graphQL(esService).execute("""
                {
                  therapyOverview { study_short_name program_name program_short_name ctep_disease_term }
                  therapy_data_files { study_short_name program_short_name }
                }
                """);

        assertTrue(result.getErrors().isEmpty(), () -> result.getErrors().toString());
        Map<?, ?> data = result.getData();
        List<?> rows = assertInstanceOf(List.class, data.get("therapyOverview"));
        Map<?, ?> first = assertInstanceOf(Map.class, rows.get(0));
        Map<?, ?> second = assertInstanceOf(Map.class, rows.get(1));
        assertEquals(List.of("Zulu", "Alpha"), first.get("study_short_name"));
        assertEquals(List.of("Alpha", "Zulu"), second.get("study_short_name"));
        assertEquals(List.of("Already scalar"), first.get("program_name"));
        assertEquals(List.of(), first.get("program_short_name"));
        assertNull(second.get("program_name"));
        assertEquals("One", first.get("ctep_disease_term"));
        assertNull(second.get("ctep_disease_term"));
        Map<?, ?> third = assertInstanceOf(Map.class, rows.get(2));
        assertEquals(List.of("Single"), third.get("study_short_name"));
        assertEquals(Arrays.asList(null, "NA", "NA"), third.get("program_short_name"));
        List<?> files = assertInstanceOf(List.class, data.get("therapy_data_files"));
        for (Object file : files.subList(0, 2)) {
            Map<?, ?> row = assertInstanceOf(Map.class, file);
            assertEquals("Alpha", row.get("study_short_name"));
            assertNull(row.get("program_short_name"));
        }
        assertEquals(Map.of("study_short_name", "Single", "program_short_name", "NA"), files.get(2));
    }

    @Test
    public void therapyDisplaysReportUnexpectedIndexedValueTypes() throws Exception {
        ESService esService = mock(ESService.class);
        Map<String, Object> invalidOverviewRow = Map.of(
                "ctep_disease_term", List.of(17),
                "program_short_name", List.of("NA"));
        Map<String, Object> invalidFileRow = Map.of(
                "study_short_name", List.of(17),
                "program_short_name", 17);
        when(esService.<Object>elasticMultiSend(anyList())).thenReturn(Map.of(
                "therapyOverview", List.of(invalidOverviewRow),
                "therapy_data_files", List.of(invalidFileRow)));

        ExecutionResult result = graphQL(esService).execute("""
                {
                  therapyOverview { ctep_disease_term program_short_name }
                  therapy_data_files { study_short_name program_short_name }
                }
                """);

        assertEquals(3, result.getErrors().size(), () -> result.getErrors().toString());
        for (String type : List.of("TherapyOverview", "TherapyDataFile")) {
            List<String> fields = "TherapyOverview".equals(type)
                    ? List.of("ctep_disease_term")
                    : List.of("study_short_name", "program_short_name");
            for (String field : fields) {
                assertTrue(result.getErrors().stream()
                                .anyMatch(error -> error.getMessage().contains(type + "." + field)),
                        () -> "Unexpected value type should produce a GraphQL error: " + result.getErrors());
            }
        }
    }
}
