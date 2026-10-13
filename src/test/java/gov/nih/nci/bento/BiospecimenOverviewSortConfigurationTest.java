package gov.nih.nci.bento;

import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BiospecimenOverviewSortConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    public void biospecimenOverviewDefaultsToSpecimenRecordId() throws Exception {
        String schema;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input, "GraphQL schema should be present");
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        ObjectTypeDefinition queryType = new SchemaParser().parse(schema)
                .getType("QueryType", ObjectTypeDefinition.class)
                .orElseThrow(() -> new AssertionError("QueryType should exist"));
        var query = queryType.getFieldDefinitions().stream()
                .filter(field -> "biospecimenOverview".equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("biospecimenOverview should exist"));
        var orderBy = query.getInputValueDefinitions().stream()
                .filter(argument -> "order_by".equals(argument.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("order_by should exist"));
        assertEquals("specimen_record_id", ((StringValue) orderBy.getDefaultValue()).getValue());

        Map<String, Object> config;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("yaml/single_search_es.yml")) {
            assertNotNull(input, "Search configuration should be present");
            config = new Yaml().load(input);
        }
        List<Map<String, Object>> queries = (List<Map<String, Object>>) config.get("queries");
        Map<String, Object> search = queries.stream()
                .filter(entry -> "biospecimenOverview".equals(entry.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("biospecimenOverview search should exist"));
        assertEquals("specimen_record_id", ((Map<String, Object>) search.get("filter")).get("defaultSortField"));
    }
}
