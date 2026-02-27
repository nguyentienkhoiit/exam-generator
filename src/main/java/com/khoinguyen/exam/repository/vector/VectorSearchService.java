package com.khoinguyen.exam.repository.vector;

import com.khoinguyen.exam.service.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final VectorStore vectorStore;

    public VectorSearchService(@Qualifier("ollamaVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Spring AI vector stores often attach "distance" (lower is better) or "score" (higher is better) to metadata.
     * We normalize to similarity in [0,1]-ish via 1/(1+distance) when distance is present.
     */
    private static double extractVectorSimilarity(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return 0.0;
        }
        Object scoreObj = metadata.get("score");
        if (scoreObj instanceof Number n) {
            return clamp01(n.doubleValue());
        }
        Object distObj = metadata.get("distance");
        if (distObj instanceof Number n) {
            double dist = Math.max(0.0, n.doubleValue());
            return clamp01(1.0 / (1.0 + dist));
        }
        return 0.0;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String escapeForFilter(String s) {
        return s.replace("'", "''");
    }

    public List<ScoredChunk> vectorSearch(String fileId, String query, int topK) {
        String filterExpression = "fileId == '" + escapeForFilter(fileId) + "'";

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterExpression)
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);

        List<ScoredChunk> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            UUID id = UUID.fromString(d.getId());
            Map<String, Object> md = d.getMetadata();
            double score = extractVectorSimilarity(md);

            out.add(new ScoredChunk(
                    id,
                    fileId,
                    d.getText(),
                    md,
                    score,
                    0.0,
                    score
            ));
        }

        log.info("event=pgvector_search fileId={} topK={} returned={}", fileId, topK, out.size());
        return out;
    }
}