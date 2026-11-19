package gov.nih.nci.bento;

import gov.nih.nci.bento.graphql.BentoGraphQL;
import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento.service.RedisService;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "es.filter.enabled=true")
public class TherapyOverviewMergedSchemaTest {
    @Autowired
    private BentoGraphQL bentoGraphQL;

    @MockitoBean
    private ESService esService;

    @MockitoBean
    private RedisService redisService;

    @Test
    public void mergedPrivateSchemaUsesTherapyDisplayFieldFetchers() throws Exception {
        Map<String, Object> indexedRow = Map.of(
                "study_short_name", List.of("CMB"),
                "program_name", List.of("Not Applicable"),
                "program_short_name", List.of("NA"),
                "ctep_disease_term", List.of("Plasma Cell Myeloma"));
        when(esService.<Object>elasticMultiSend(anyList())).thenReturn(Map.of(
                "therapyOverview", List.of(indexedRow),
                "therapy_data_files", List.of(indexedRow)));

        ExecutionResult result = bentoGraphQL.getPrivateGraphQL().execute("""
                {
                  therapyOverview { study_short_name program_name program_short_name ctep_disease_term }
                  therapy_data_files { study_short_name program_name program_short_name ctep_disease_term }
                }
                """);

        assertTrue(result.getErrors().isEmpty(), () -> result.getErrors().toString());
        Map<?, ?> data = result.getData();
        List<?> rows = assertInstanceOf(List.class, data.get("therapyOverview"));
        assertEquals(Map.of(
                "study_short_name", List.of("CMB"),
                "program_name", List.of("Not Applicable"),
                "program_short_name", List.of("NA"),
                "ctep_disease_term", "Plasma Cell Myeloma"), rows.get(0));
        List<?> files = assertInstanceOf(List.class, data.get("therapy_data_files"));
        assertEquals(Map.of(
                "study_short_name", "CMB",
                "program_name", List.of("Not Applicable"),
                "program_short_name", "NA",
                "ctep_disease_term", List.of("Plasma Cell Myeloma")), files.get(0));
    }
}
