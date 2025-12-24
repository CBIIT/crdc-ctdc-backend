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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class GraphQLControllerTest {

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

    @Test
    public void getVersion_shouldReturnVersionFromConfig() {
        // Arrange
        when(config.getBentoApiVersion()).thenReturn("2.2.0");

        // Act
        ResponseEntity<String> response = controller.getVersion();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("2.2.0"));
        verify(config, times(1)).getBentoApiVersion();
    }

    // --- /v1/graphql/ POST Endpoint Tests ---

    @Test
    public void getPrivateGraphQLResponse_validQuery_whenQueriesEnabled() {
        // Arrange
        String requestBody = "{\"query\": \"{ testField }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
        when(executionResult.toSpecification()).thenReturn(Map.of("data", Map.of("testField", "value")));

        // Act
        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(config).isAllowGraphQLQuery();
        verify(graphQL).execute(any(ExecutionInput.class));
    }

    // @Test
    // public void getPrivateGraphQLResponse_validMutation_whenMutationsEnabled() {
    //     // Arrange
    //     String requestBody = "{\"query\": \"mutation { createItem }\", \"variables\": {}}";
    //     HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

    //     when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
    //     when(config.isAllowGraphQLQuery()).thenReturn(true);
    //     when(graphQL.execute(any(ExecutionInput.class))).thenReturn(executionResult);
    //     when(executionResult.toSpecification()).thenReturn(Map.of("data", Map.of("createItem", "success")));

    //     // Act
    //     ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

    //     // Assert
    //     assertEquals(HttpStatus.OK, response.getStatusCode());
    //     assertNotNull(response.getBody());
    //     verify(config).isAllowGraphQLQuery();
    //     verify(graphQL).execute(any(ExecutionInput.class));
    // }

    @Test
    public void getPrivateGraphQLResponse_query_whenQueriesDisabled() {
        // Arrange
        String requestBody = "{\"query\": \"{ testField }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        lenient().when(config.isAllowGraphQLQuery()).thenReturn(false);

        // Act
        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        // Assert
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("disabled"));
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }

    @Test
    public void getPrivateGraphQLResponse_mutation_whenMutationsDisabled() {
        // Arrange
        String requestBody = "{\"query\": \"mutation { createItem }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);
        lenient().when(config.isAllowGraphQLQuery()).thenReturn(false);

        // Act
        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        // Assert
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("disabled"));
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }

    @Test
    public void getPrivateGraphQLResponse_invalidGraphQLQuery() {
        // Arrange - valid JSON but missing query field (null query)
        String requestBody = "{\"query\": null, \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);

        // Act
        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }

    @Test
    public void getPrivateGraphQLResponse_unrecognizedOperation() {
        // Arrange - subscription is not recognized
        String requestBody = "{\"query\": \"subscription { onUpdate }\", \"variables\": {}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);

        // Act
        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("not recognized"));
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }

    @Test
    public void getPrivateGraphQLResponse_parameterListExceedsLimit() {
        // Arrange - create a list with more than 1000 items
        List<String> largeList = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            largeList.add("item" + i);
        }
        String largeListJson = largeList.toString().replace("[", "[\"").replace(", ", "\", \"").replace("]", "\"]");
        String requestBody = "{\"query\": \"{ testField }\", \"variables\": {\"ids\": " + largeListJson + "}}";
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody);

        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(graphQL);

        // Act
        ResponseEntity<String> response = controller.getPrivateGraphQLResponse(httpEntity);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(graphQL, never()).execute(any(ExecutionInput.class));
    }
}