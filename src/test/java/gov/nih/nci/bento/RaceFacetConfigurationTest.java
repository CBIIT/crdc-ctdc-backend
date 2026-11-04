package gov.nih.nci.bento;

import gov.nih.nci.bento.model.search.filter.AggregationFilter;
import gov.nih.nci.bento.model.search.filter.FilterParam;
import gov.nih.nci.bento.model.search.query.QueryFactory;
import graphql.language.AstPrinter;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.TermsQueryBuilder;
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RaceFacetConfigurationTest {

    @Test
    void allRaceProjectionsNormalizeScalarsListsAndCollectedLists() throws Exception {
        Map<String, String> sources = Map.of(
                "widgets_facets_counts", "races",
                "tab_participants", "[coalesce(demo_latest.race, [])]",
                "tab_biospecimens", "[coalesce(demo.race, [])]",
                "tab_data_files", "race_list",
                "cart_data_files", "race_list",
                "biospecimen_data_file", "[coalesce(demo.race, [])]",
                "study_file_overview", "[coalesce(demo.race, [])]",
                "therapy_count", "COLLECT(DISTINCT demo.race)",
                "clinical_data_tab", "[coalesce(dm.race, [])]");
        List<Map<String, Object>> indices = entries(load("yaml/es_indices_ctdc.yml").get("Indices"));

        for (Map.Entry<String, String> source : sources.entrySet()) {
            Map<String, Object> index = named(indices, "index_name", source.getKey());
            String query = ((String) index.get("cypher_query")).replaceAll("\\s+", " ");
            String normalization = "apoc.coll.toSet([r IN apoc.coll.flatten([value IN apoc.coll.flatten("
                    + source.getValue() + ") | split(value, '|')]) "
                    + "WHERE r IS NOT NULL AND trim(r) <> '' | trim(r)])";
            assertTrue(query.contains(normalization), source.getKey() + " must normalize every race entry");
            if (!"clinical_data_tab".equals(source.getKey())) {
                Map<?, ?> mapping = (Map<?, ?>) index.get("mapping");
                assertEquals("keyword", ((Map<?, ?>) mapping.get("race")).get("type"));
                assertTrue(query.contains(normalization + " AS race"));
            } else {
                assertTrue(query.contains("race: " + normalization));
            }
        }

        String widgets = (String) named(indices, "index_name", "widgets_facets_counts").get("cypher_query");
        assertTrue(widgets.contains("RETURN DISTINCT"));
        assertTrue(widgets.contains("sb.participant_id as participant_id"));
        assertFalse(widgets.toUpperCase().contains("UNWIND"),
                "Normalizing races must not expand participant rows");
    }

    @Test
    void raceFacetAndParticipantTotalRetainTheirOwnAggregationFields() throws Exception {
        List<Map<String, Object>> fields = entries(named(
                entries(load("yaml/facet_search_es.yml").get("queries")),
                "name", "searchParticipants").get("returnFields"));
        Map<?, ?> race = (Map<?, ?>) named(fields, "name", "participantCountByRace").get("filter");
        Map<?, ?> excludingRace = (Map<?, ?>) named(fields, "name", "filterParticipantCountByRace").get("filter");
        assertEquals("aggregation", race.get("type"));
        assertEquals("race", race.get("selectedField"));
        assertNotEquals(true, race.get("ignoreSelectedField"));
        assertEquals("race", excludingRace.get("selectedField"));
        assertEquals(true, excludingRace.get("ignoreSelectedField"));
        Map<String, Object> total = named(fields, "name", "numberOfParticipants");
        assertEquals("participant_id", ((Map<?, ?>) total.get("filter")).get("selectedField"));
        assertEquals("count_bucket_keys", ((Map<?, ?>) total.get("result")).get("method"));
        Map<?, ?> combined = (Map<?, ?>) named(fields, "name", "racesAndEthnicities").get("filter");
        assertEquals("race", combined.get("selectedField"));
        assertEquals("ethnicity", combined.get("subAggSelectedField"));
    }

    @Test
    void raceMultiselectUsesOneTermsFilterAndKeepsOtherFacets() {
        FilterParam param = FilterParam.builder()
                .args(Map.of("race", List.of("White", "Asian"), "sex", List.of("Female")))
                .selectedField("race").build();
        BoolQueryBuilder query = (BoolQueryBuilder) new QueryFactory(param).getQuery();
        assertEquals(2, query.filter().size(), "Different facets remain ANDed");
        TermsQueryBuilder race = query.filter().stream()
                .map(TermsQueryBuilder.class::cast)
                .filter(filter -> "race".equals(filter.fieldName())).findFirst().orElseThrow();
        assertEquals(List.of("White", "Asian"), race.values(),
                "A single terms query matches either value within a keyword array, without duplicating hits");

        SearchSourceBuilder source = new AggregationFilter(param).getSourceFilter();
        TermsAggregationBuilder aggregation = (TermsAggregationBuilder)
                source.aggregations().getAggregatorFactories().iterator().next();
        assertEquals("race", aggregation.field());
        assertEquals(0, source.size());
    }

    @Test
    void selfExcludingRaceFacetKeepsOtherFilters() {
        FilterParam param = FilterParam.builder()
                .args(Map.of("race", List.of("White", "Asian"), "sex", List.of("Female")))
                .selectedField("race").isIgnoreSelectedField(true).build();
        BoolQueryBuilder query = (BoolQueryBuilder) new QueryFactory(param).getQuery();
        assertEquals(1, query.filter().size());
        TermsQueryBuilder remaining = (TermsQueryBuilder) query.filter().get(0);
        assertEquals("sex", remaining.fieldName());
        assertEquals(List.of("Female"), remaining.values());
    }

    @Test
    void singleRaceUsesTheSameExactTermsFilter() {
        FilterParam param = FilterParam.builder().args(Map.of("race", List.of("White"))).build();
        BoolQueryBuilder query = (BoolQueryBuilder) new QueryFactory(param).getQuery();
        assertEquals(1, query.filter().size());
        TermsQueryBuilder race = (TermsQueryBuilder) query.filter().get(0);
        assertEquals("race", race.fieldName());
        assertEquals(List.of("White"), race.values());
    }

    @Test
    void raceOutputsAndExistingFilterArgumentsAreLists() throws Exception {
        String schema;
        try (InputStream input = ClassLoader.getSystemResourceAsStream("graphql/crdc-ctdc-private-es.graphql")) {
            assertNotNull(input);
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);
        for (String name : List.of("GS_participant", "Demographic", "StudyFileOverviewInfo",
                "ParticipantOverview", "ClinicalDemographic", "BiospecimenOverview", "FileOverview")) {
            ObjectTypeDefinition type = registry.getType(name, ObjectTypeDefinition.class).orElseThrow();
            assertEquals("[String]", AstPrinter.printAstCompact(type.getFieldDefinitions().stream()
                    .filter(field -> "race".equals(field.getName())).findFirst().orElseThrow().getType()), name);
        }
        ObjectTypeDefinition query = registry.getType("QueryType", ObjectTypeDefinition.class).orElseThrow();
        Set<String> filteredQueries = Set.of("searchParticipants", "participantOverview", "biospecimenOverview",
                "fileOverview", "participant_data_files", "biospecimen_data_files");
        for (String name : filteredQueries) {
            assertEquals("[String]", AstPrinter.printAstCompact(query.getFieldDefinitions().stream()
                    .filter(field -> name.equals(field.getName())).findFirst().orElseThrow()
                    .getInputValueDefinitions().stream().filter(arg -> "race".equals(arg.getName()))
                    .findFirst().orElseThrow().getType()), name);
        }
    }

    private static Map<String, Object> load(String resource) throws Exception {
        try (InputStream input = ClassLoader.getSystemResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> named(List<Map<String, Object>> entries, String key, String name) {
        return entries.stream().filter(entry -> name.equals(entry.get(key))).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + name));
    }
}
