package gov.nih.nci.bento_ri.model;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

public class TherapyOverviewFieldFetcher implements DataFetcher<String> {
    private static final Logger logger = LogManager.getLogger(TherapyOverviewFieldFetcher.class);

    @Override
    public String get(DataFetchingEnvironment env) {
        String field = env.getFieldDefinition().getName();
        Object source = env.getSource();
        if (!(source instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Expected a row object for TherapyOverview." + field);
        }
        Object value = row.get(field);
        if (value == null) return null;
        if (value instanceof String text) return text;
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("Expected a string or list of strings for TherapyOverview." + field);
        }

        String first = null;
        boolean multiple = false;
        for (Object item : values) {
            if (item == null) continue;
            if (!(item instanceof String text)) {
                throw new IllegalStateException("Expected a string or list of strings for TherapyOverview." + field);
            }
            if (first == null) {
                first = text;
            } else if (!first.equals(text)) {
                multiple = true;
                if (text.compareTo(first) < 0) first = text;
            }
        }
        if (multiple) {
            logger.error("Multiple distinct values for TherapyOverview.{}; returning the first sorted value", field);
        }
        return first;
    }
}
