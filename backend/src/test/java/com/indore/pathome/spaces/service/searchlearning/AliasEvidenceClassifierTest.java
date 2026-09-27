package com.indore.pathome.spaces.service.searchlearning;

import com.indore.pathome.spaces.entity.SearchQueryEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AliasEvidenceClassifierTest {
    @ParameterizedTest
    @CsvSource({"ENTITY_MATCH,true,STRONG", "LOCALITY,true,STRONG", "SEARCH_QUERY,true,STRONG",
            "QUERY_INTENT,true,MODERATE", "QUERY_INTENT,false,WEAK", "SEARCH_ANYWAY,true,IGNORED", "UNSUPPORTED_CITY,true,IGNORED"})
    void explicitTaxonomy(String type,boolean agreement,String expected) {
        var e=event(type); assertEquals(expected,AliasEvidenceClassifier.classify(e,true,agreement).name());
    }
    @Test void impressionsSearchExecutionInvalidTargetAndMissingSessionsNeverTrain() {
        var e=event("LOCALITY"); e.setEventType("SUGGESTION_SHOWN");
        assertEquals("IGNORED",AliasEvidenceClassifier.classify(e,true,true).name());
        e.setEventType("SEARCH_EXECUTED"); assertEquals("IGNORED",AliasEvidenceClassifier.classify(e,true,true).name());
        e.setEventType("SUGGESTION_SELECTED"); assertEquals("IGNORED",AliasEvidenceClassifier.classify(e,false,true).name());
        e.setSessionHash(null); assertEquals("IGNORED",AliasEvidenceClassifier.classify(e,true,true).name());
    }
    private SearchQueryEvent event(String type) {
        var e=new SearchQueryEvent(); e.setEventType("SUGGESTION_SELECTED");e.setSelectedType(type);e.setSessionHash("anonymous-hash"); return e;
    }
}
