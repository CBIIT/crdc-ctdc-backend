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

    @Test
    public void therapyOverviewReturnsStringsAndKeepsTherapyFileListsAndFilters() throws Exception {
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
                  therapy_data_files {
                    study_short_name
                    program_name
                    program_short_name
                    ctep_disease_term
                  }
                }
                """);

        assertTrue(result.getErrors().isEmpty(), () -> result.getErrors().toString());
        Map<?, ?> data = result.getData();
        List<?> overview = assertInstanceOf(List.class, data.get("therapyOverview"));
        assertEquals(Map.of(
                "study_short_name", "CMB",
                "display_program", "Not Applicable",
                "program_short_name", "NA",
                "ctep_disease_term", "Plasma Cell Myeloma"), overview.get(0));
        List<?> files = assertInstanceOf(List.class, data.get("therapy_data_files"));
        assertEquals(indexedRow, files.get(0));

        verify(esService).elasticMultiSend(argThat(requests -> {
            if (requests.size() != 1 || !"therapyOverview".equals(requests.get(0).getName())) return false;
            QueryBuilder query = requests.get(0).getRequest().source().query();
            if (!(query instanceof BoolQueryBuilder bool)) return false;
            Map<String, List<Object>> filters = new HashMap<>();
            for (QueryBuilder filter : bool.filter()) {
                if (!(filter instanceof TermsQueryBuilder terms)) return false;
                filters.put(terms.fieldName(), terms.values());
            }
            return filters.equals(Map.of(
                    "study_short_name", List.of("CMB"),
                    "program_name", List.of("Not Applicable"),
                    "program_short_name", List.of("NA"),
                    "ctep_disease_term", List.of("Plasma Cell Myeloma")));
        }));
    }

    @Test
    public void therapyOverviewHandlesScalarMissingAndMultipleIndexedValues() throws Exception {
        Map<String, Object> firstRow = new HashMap<>();
        firstRow.put("study_short_name", List.of("Zulu", "Alpha"));
        firstRow.put("program_name", "Already scalar");
        firstRow.put("program_short_name", List.of());
        firstRow.put("ctep_disease_term", Arrays.asList(null, "One"));
        Map<String, Object> secondRow = Map.of(
                "study_short_name", List.of("Alpha", "Zulu"),
                "ctep_disease_term", List.of());
        ESService esService = mock(ESService.class);
        when(esService.<Object>elasticMultiSend(anyList())).thenReturn(
                Map.of("therapyOverview", List.of(firstRow, secondRow)));

        ExecutionResult result = graphQL(esService).execute("""
                { therapyOverview { study_short_name program_name program_short_name ctep_disease_term } }
                """);

        assertTrue(result.getErrors().isEmpty(), () -> result.getErrors().toString());
        Map<?, ?> data = result.getData();
        List<?> rows = assertInstanceOf(List.class, data.get("therapyOverview"));
        Map<?, ?> first = assertInstanceOf(Map.class, rows.get(0));
        Map<?, ?> second = assertInstanceOf(Map.class, rows.get(1));
        assertEquals("Alpha", first.get("study_short_name"));
        assertEquals("Alpha", second.get("study_short_name"));
        assertEquals("Already scalar", first.get("program_name"));
        assertNull(first.get("program_short_name"));
        assertNull(second.get("program_name"));
        assertEquals("One", first.get("ctep_disease_term"));
        assertNull(second.get("ctep_disease_term"));
    }

    @Test
    public void therapyOverviewReportsUnexpectedIndexedValueTypes() throws Exception {
        ESService esService = mock(ESService.class);
        when(esService.<Object>elasticMultiSend(anyList())).thenReturn(Map.of(
                "therapyOverview", List.of(Map.of("study_short_name", List.of(17)))));

        ExecutionResult result = graphQL(esService).execute("{ therapyOverview { study_short_name } }");

        assertTrue(result.getErrors().stream().anyMatch(error -> error.getMessage().contains("study_short_name")),
                () -> "Unexpected value type should produce a GraphQL error: " + result.getErrors());
    }
}
