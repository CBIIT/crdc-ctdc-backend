package gov.nih.nci.bento;

import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParticipantOverviewConfigurationTest {

    @Test
    public void participantOverviewSchemaExposesSurvivalStatus() throws Exception {
        String schema;
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertTrue("GraphQL schema should be present", inputStream != null);
            schema = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);
        Optional<ObjectTypeDefinition> participantOverviewType = registry.getType("ParticipantOverview", ObjectTypeDefinition.class);

        assertTrue("ParticipantOverview type should exist", participantOverviewType.isPresent());
        assertTrue(
                "ParticipantOverview should expose survival_status",
                participantOverviewType.get().getFieldDefinitions().stream()
                        .anyMatch(fieldDefinition -> "survival_status".equals(fieldDefinition.getName()))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tabParticipantsIndexesSurvivalStatus() {
        Map<String, Object> yamlConfig;
        Yaml yaml = new Yaml();
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("yaml/es_indices_ctdc.yml")) {
            assertTrue("Index configuration should be present", inputStream != null);
            yamlConfig = yaml.load(inputStream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load tab_participants configuration", e);
        }

        List<Map<String, Object>> indices = (List<Map<String, Object>>) yamlConfig.get("indices");
        Map<String, Object> tabParticipants = indices.stream()
                .filter(index -> "tab_participants".equals(index.get("index_name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tab_participants index is missing"));

        Map<String, Object> mapping = (Map<String, Object>) tabParticipants.get("mapping");
        assertTrue("tab_participants mapping should contain survival_status", mapping.containsKey("survival_status"));
        assertEquals("keyword", ((Map<String, Object>) mapping.get("survival_status")).get("type"));

        String cypherQuery = (String) tabParticipants.get("cypher_query");
        assertTrue("tab_participants cypher should pull participant_status nodes", cypherQuery.contains("OPTIONAL MATCH (sb)<-[:of_participant]-(ps:participant_status)"));
        assertTrue("tab_participants cypher should return survival_status", cypherQuery.contains("survival_status                        AS survival_status"));
    }
}
