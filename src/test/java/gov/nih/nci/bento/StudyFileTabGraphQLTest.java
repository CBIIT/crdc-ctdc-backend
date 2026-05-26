package gov.nih.nci.bento;

import gov.nih.nci.bento.controller.GraphQLController;
import gov.nih.nci.bento.graphql.BentoGraphQL;
import gov.nih.nci.bento.model.ConfigurationDAO;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for GraphQL queries added in CTDC-2034:
 * StudyFileTabByStudyShortName, numberOfStudyFiles, and related count queries.
 */
@ExtendWith(MockitoExtension.class)
public class StudyFileTabGraphQLTest {

    @Mock
    private ConfigurationDAO config;

    @Mock
    private BentoGraphQL bentoGraphQL;

    @Mock
    private GraphQL graphQL;

    @Mock
    private ExecutionResult executionResult;

    private GraphQLController controller;

    @BeforeEach
    public void setUp() {
        controller = new GraphQLController(config, bentoGraphQL);
    }

    // --- StudyFileTabByStudyShortName ---

    @Test
    public void studyFileTabByStudyShortName_validQuery_returnsOk() {
        String requestBody = "{\"query\": \"{ StudyFileTabByStudyShortName(study_short_name: \\\"TEST\\\") { study_id data_file_name } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("StudyFileTabByStudyShortName", List.of(
                        Map.of("study_id", "S001", "data_file_name", "file1.csv")
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    @Test
    public void studyFileTabByStudyShortName_queriesDisabled_returnsForbidden() {
        String requestBody = "{\"query\": \"{ StudyFileTabByStudyShortName(study_short_name: \\\"TEST\\\") { study_id } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        lenient().when(config.isAllowGraphQLQuery()).thenReturn(false);

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("disabled"));
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }

    @Test
    public void studyFileTabByStudyShortName_returnsFileInfoNestedFields() {
        String requestBody = "{\"query\": \"{ StudyFileTabByStudyShortName(study_short_name: \\\"TEST\\\") { file_info { data_file_uuid data_file_format } } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("StudyFileTabByStudyShortName", List.of(
                        Map.of("file_info", List.of(
                                Map.of("data_file_uuid", "uuid-001", "data_file_format", "CSV")
                        ))
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    @Test
    public void studyFileTabByStudyShortName_returnsBiospecimenInfoNestedFields() {
        String requestBody = "{\"query\": \"{ StudyFileTabByStudyShortName(study_short_name: \\\"TEST\\\") { biospecimen_info { specimen_record_id } } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("StudyFileTabByStudyShortName", List.of(
                        Map.of("biospecimen_info", List.of(
                                Map.of("specimen_record_id", "spec-001")
                        ))
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    // --- numberOfStudyFiles ---

    @Test
    public void numberOfStudyFiles_validQuery_returnsCount() {
        String requestBody = "{\"query\": \"{ numberOfStudyFiles }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("numberOfStudyFiles", 42)
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    // --- studyFileTabCountByDataFileType / Format ---

    @Test
    public void studyFileTabCountByDataFileType_validQuery_returnsGroupCounts() {
        String requestBody = "{\"query\": \"{ studyFileTabCountByDataFileType { group count } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("studyFileTabCountByDataFileType", List.of(
                        Map.of("group", "Clinical", "count", 10),
                        Map.of("group", "Genomic", "count", 5)
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    @Test
    public void studyFileTabCountByDataFileFormat_validQuery_returnsGroupCounts() {
        String requestBody = "{\"query\": \"{ studyFileTabCountByDataFileFormat { group count } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("studyFileTabCountByDataFileFormat", List.of(
                        Map.of("group", "CSV", "count", 8),
                        Map.of("group", "TSV", "count", 3)
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    // --- filterStudyFileTabCountByDataFileType / Format ---

    @Test
    public void filterStudyFileTabCountByDataFileType_validQuery_returnsFilteredGroupCounts() {
        String requestBody = "{\"query\": \"{ filterStudyFileTabCountByDataFileType { group count } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("filterStudyFileTabCountByDataFileType", List.of(
                        Map.of("group", "Clinical", "count", 4)
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    @Test
    public void filterStudyFileTabCountByDataFileFormat_validQuery_returnsFilteredGroupCounts() {
        String requestBody = "{\"query\": \"{ filterStudyFileTabCountByDataFileFormat { group count } }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of(
                "data", Map.of("filterStudyFileTabCountByDataFileFormat", List.of(
                        Map.of("group", "CSV", "count", 2)
                ))
        ));

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    // --- Parameter limit guard ---

    @Test
    public void studyFileTabByStudyShortName_parameterListExceedsLimit_returnsBadRequest() {
        java.util.List<String> largeList = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            largeList.add("item" + i);
        }
        String largeListJson = largeList.toString().replace("[", "[\"").replace(", ", "\", \"").replace("]", "\"]");
        String requestBody = "{\"query\": \"{ StudyFileTabByStudyShortName(study_short_name: \\\"TEST\\\") { study_id } }\", \"variables\": {\"ids\": " + largeListJson + "}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);

        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }
}
