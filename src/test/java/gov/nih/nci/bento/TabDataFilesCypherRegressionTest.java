package gov.nih.nci.bento;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TabDataFilesCypherRegressionTest {

    private static final String YAML_PATH = "src/main/resources/yaml/es_indices_ctdc.yml";

    @Test
    public void tabDataFilesCypher_shouldContainStudyOnlyFallbackLogic() throws IOException {
        String yaml = Files.readString(Paths.get(YAML_PATH), StandardCharsets.UTF_8);

        // Locate just the tab_data_files index block so assertions stay scoped to this ticket behavior.
        String blockStart = "- index_name: tab_data_files";
        String blockEnd = "- index_name: cart_data_files";
        int start = yaml.indexOf(blockStart);
        int end = yaml.indexOf(blockEnd);

        assertTrue(start >= 0, "tab_data_files block must exist");
        assertTrue(end > start, "cart_data_files block must exist after tab_data_files");

        String tabDataFilesBlock = yaml.substring(start, end);

        assertTrue(tabDataFilesBlock.contains("OPTIONAL MATCH (f)-[:associated_with]-(study_direct:study)"));
        assertTrue(tabDataFilesBlock.contains("OPTIONAL MATCH (study_part:participant)-[:belongs_to]->(study_expand:study)"));
        assertTrue(tabDataFilesBlock.contains("AND size(associations) = 1 AND 'study' IN associations"));
        assertTrue(tabDataFilesBlock.contains("AS effective_subs"));
        assertTrue(tabDataFilesBlock.contains("WHERE dPart IN effective_subs"));
        assertTrue(tabDataFilesBlock.contains("head([x IN effective_subs WHERE x.participant_id IS NOT NULL | x.participant_id]) AS participant_id"));
    }
}
