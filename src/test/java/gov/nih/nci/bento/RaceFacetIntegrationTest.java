package gov.nih.nci.bento;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gov.nih.nci.bento.model.search.filter.AggregationFilter;
import gov.nih.nci.bento.model.search.filter.FilterParam;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RaceFacetIntegrationTest {
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void normalizedRaceFacetsAndFiltersCountParticipantsWithoutDuplicatingResults() throws Exception {
        String neo4j = System.getProperty("race.test.neo4j.uri");
        String openSearch = System.getProperty("race.test.opensearch.url");
        assumeTrue(neo4j != null && openSearch != null,
                "Requires disposable Neo4j (APOC enabled, auth disabled) and OpenSearch instances");
        String index = "race-facet-test-" + UUID.randomUUID();

        List<Map<String, Object>> fixtures = List.of(
                fixture("p1", "White|Asian"),
                fixture("p2", "White|American Indian or Alaska Native"),
                fixture("p3", "Black or African American|American Indian or Alaska Native"),
                fixture("p4", List.of(" White ", "Asian", "White", "", " | ")),
                fixture("p5", "White"),
                fixture("p6", " | || "),
                fixture("p7", null),
                fixture("p8", List.of("White|Asian", " Asian ")));
        Map<String, Set<String>> expected = Map.of(
                "p1", Set.of("White", "Asian"),
                "p2", Set.of("White", "American Indian or Alaska Native"),
                "p3", Set.of("Black or African American", "American Indian or Alaska Native"),
                "p4", Set.of("White", "Asian"), "p5", Set.of("White"),
                "p6", Set.of(), "p7", Set.of(), "p8", Set.of("White", "Asian"));

        try (var driver = GraphDatabase.driver(neo4j, AuthTokens.none());
             var session = driver.session()) {
            assertAllProjectionsNormalize(session, fixtures, expected);
            session.run("CREATE (s:study:RaceFacetTest {study_short_name: 'race-fixtures'}) "
                    + "WITH s UNWIND $fixtures AS fixture "
                    + "CREATE (p:participant:RaceFacetTest {participant_id: fixture.id})-[:belongs_to]->(s) "
                    + "CREATE (d:demographic:RaceFacetTest {race: fixture.race, "
                    + "sex: CASE WHEN fixture.id = 'p2' THEN 'Male' ELSE 'Female' END, "
                    + "ethnicity: 'Not Hispanic or Latino'})-[:of_participant]->(p)",
                    Map.of("fixtures", fixtures)).consume();
            try {
                session.run("MATCH (p:participant:RaceFacetTest {participant_id: 'p4'}) "
                        + "CREATE (:demographic:RaceFacetTest {race: ['Asian', 'White'], sex: 'Female', "
                        + "ethnicity: 'Not Hispanic or Latino'})-[:of_participant]->(p)").consume();
                List<Map<String, Object>> documents = session.run(widgetQuery()).list(Record::asMap);
                assertEquals(8, documents.size(), "Multiple races/demographics must not expand participant rows");
                for (Map<String, Object> document : documents) {
                    List<?> races = (List<?>) document.get("race");
                    assertEquals(expected.get(document.get("participant_id")), Set.copyOf(races));
                    assertEquals(Set.copyOf(races).size(), races.size(), "Race values must be deduplicated");
                }

                request(openSearch, "PUT", "/" + index, gson.toJson(Map.of("mappings", Map.of(
                        "properties", Map.of("race", Map.of("type", "keyword"),
                                "participant_id", Map.of("type", "keyword"),
                                "sex", Map.of("type", "keyword"))))));
                try {
                    StringBuilder bulk = new StringBuilder();
                    for (Map<String, Object> document : documents) {
                        bulk.append(gson.toJson(Map.of("index", Map.of("_id", document.get("participant_id")))))
                                .append('\n');
                        bulk.append(gson.toJson(Map.of("participant_id", document.get("participant_id"),
                                "race", document.get("race"), "sex", document.get("sex")))).append('\n');
                    }
                    JsonObject indexed = request(openSearch, "POST", "/" + index + "/_bulk?refresh=true", bulk.toString());
                    assertFalse(indexed.get("errors").getAsBoolean(), indexed.toString());

                    JsonObject all = search(openSearch, index, Map.of(), false);
                    assertHits(all, Set.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8"));
                    Map<String, Long> counts = buckets(all, "race_counts");
                    assertEquals(Map.of("White", 5L, "Asian", 3L,
                            "American Indian or Alaska Native", 2L, "Black or African American", 1L), counts);
                    assertEquals(11L, counts.values().stream().mapToLong(Long::longValue).sum());
                    assertEquals(8, buckets(all, "participant_counts").size());
                    assertHits(search(openSearch, index, Map.of("race", List.of("Asian")), false),
                            Set.of("p1", "p4", "p8"));
                    assertHits(search(openSearch, index, Map.of("race", List.of("White")), false),
                            Set.of("p1", "p2", "p4", "p5", "p8"));
                    assertHits(search(openSearch, index, Map.of("race", List.of("White", "Asian")), false),
                            Set.of("p1", "p2", "p4", "p5", "p8"));
                    assertHits(search(openSearch, index,
                            Map.of("race", List.of("White", "Asian"), "sex", List.of("Male")), false),
                            Set.of("p2"));
                    assertHits(search(openSearch, index, Map.of("race", List.of()), false),
                            Set.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8"));
                    JsonObject excludingRace = search(openSearch, index,
                            Map.of("race", List.of("Asian"), "sex", List.of("Female")), true);
                    assertHits(excludingRace, Set.of("p1", "p3", "p4", "p5", "p6", "p7", "p8"));
                    assertEquals(Map.of("White", 4L, "Asian", 3L,
                            "American Indian or Alaska Native", 1L, "Black or African American", 1L),
                            buckets(excludingRace, "race_counts"));
                } finally {
                    request(openSearch, "DELETE", "/" + index, null);
                }
            } finally {
                session.run("MATCH (n:RaceFacetTest) DETACH DELETE n").consume();
            }
        }
    }

    private static Map<String, Object> fixture(String id, Object race) {
        Map<String, Object> fixture = new HashMap<>();
        fixture.put("id", id);
        fixture.put("race", race);
        return fixture;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> indices() throws Exception {
        try (InputStream input = ClassLoader.getSystemResourceAsStream("yaml/es_indices_ctdc.yml")) {
            assertNotNull(input);
            Map<String, Object> config = new Yaml().load(input);
            return (List<Map<String, Object>>) config.get("Indices");
        }
    }

    private static String widgetQuery() throws Exception {
        return (String) indices().stream()
                .filter(index -> "widgets_facets_counts".equals(index.get("index_name")))
                .findFirst().orElseThrow().get("cypher_query");
    }

    private static void assertAllProjectionsNormalize(Session session, List<Map<String, Object>> fixtures,
                                                      Map<String, Set<String>> expected) throws Exception {
        int checked = 0;
        String prefix = "apoc.coll.toSet([r IN apoc.coll.flatten([value IN apoc.coll.flatten(";
        String suffix = "| trim(r)])";
        for (Map<String, Object> index : indices()) {
            if (!(index.get("cypher_query") instanceof String configuredQuery)) {
                continue;
            }
            String query = configuredQuery.replaceAll("\\s+", " ");
            int start = query.indexOf(prefix);
            if (start < 0) {
                continue;
            }
            String expression = query.substring(start, query.indexOf(suffix, start) + suffix.length());
            for (Map<String, Object> fixture : fixtures) {
                List<?> races = session.run("WITH $raw AS raw "
                        + "WITH CASE WHEN raw IS NULL THEN [] ELSE [raw] END AS races, "
                        + "CASE WHEN raw IS NULL THEN [] ELSE [raw] END AS race_list, {race: raw} AS demo, "
                        + "{race: raw} AS demo_latest, {race: raw} AS dm "
                        + "RETURN " + expression + " AS race",
                        java.util.Collections.singletonMap("raw", fixture.get("race")))
                        .single().get("race").asList();
                assertEquals(expected.get(fixture.get("id")), Set.copyOf(races),
                        index.get("index_name") + ": " + fixture.get("id"));
                assertEquals(Set.copyOf(races).size(), races.size());
            }
            checked++;
        }
        assertEquals(9, checked, "All scalar, collected, cart, and nested clinical projections must be checked");
    }

    private JsonObject search(String server, String index, Map<String, Object> args,
                              boolean ignoreRace) throws Exception {
        FilterParam param = FilterParam.builder().args(args).selectedField("race")
                .isIgnoreSelectedField(ignoreRace).build();
        SearchSourceBuilder source = new AggregationFilter(param).getSourceFilter()
                .size(20).trackTotalHits(true);
        source.aggregation(AggregationBuilders.terms("race_counts").field("race").size(100));
        source.aggregation(AggregationBuilders.terms("participant_counts").field("participant_id").size(100));
        return request(server, "POST", "/" + index + "/_search", source.toString());
    }

    private JsonObject request(String server, String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                method + " " + path + ": " + response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private static void assertHits(JsonObject response, Set<String> expected) {
        JsonObject hits = response.getAsJsonObject("hits");
        assertEquals(expected.size(), hits.getAsJsonObject("total").get("value").getAsInt());
        List<String> ids = new ArrayList<>();
        hits.getAsJsonArray("hits").forEach(hit -> ids.add(hit.getAsJsonObject()
                .getAsJsonObject("_source").get("participant_id").getAsString()));
        assertEquals(expected, Set.copyOf(ids));
        assertEquals(expected.size(), ids.size(), "Each participant must appear only once in results");
    }

    private static Map<String, Long> buckets(JsonObject response, String name) {
        Map<String, Long> counts = new HashMap<>();
        response.getAsJsonObject("aggregations").getAsJsonObject(name).getAsJsonArray("buckets")
                .forEach(bucket -> counts.put(bucket.getAsJsonObject().get("key").getAsString(),
                        bucket.getAsJsonObject().get("doc_count").getAsLong()));
        return counts;
    }
}
