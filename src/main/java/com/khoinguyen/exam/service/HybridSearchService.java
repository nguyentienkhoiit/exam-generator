package com.khoinguyen.exam.service;

import com.khoinguyen.exam.repository.search.ElasticsearchService;
import com.khoinguyen.exam.repository.vector.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final VectorSearchService vectorSearchService;
    private final ElasticsearchService elasticsearchService;
    private final DataSource dataSource;
    private final Executor executor;

    private final int topK;
    private final double alpha;
    private final int mmrK;
    private final double mmrLambda;

    public HybridSearchService(
            VectorSearchService vectorSearchService,
            ElasticsearchService elasticsearchService,
            DataSource dataSource,
            Executor executor,
            @Value("${exam.rag.retrieve.topK}") int topK,
            @Value("${exam.rag.retrieve.alpha}") double alpha,
            @Value("${exam.rag.retrieve.mmr.k}") int mmrK,
            @Value("${exam.rag.retrieve.mmr.lambda}") double mmrLambda
    ) {
        this.vectorSearchService = vectorSearchService;
        this.elasticsearchService = elasticsearchService;
        this.dataSource = dataSource;
        this.executor = executor;
        this.topK = topK;
        this.alpha = clamp01(alpha);
        this.mmrK = Math.max(1, mmrK);
        this.mmrLambda = clamp01(mmrLambda);
    }

    // pgvector text format: [0.1,0.2,...]
    private static double[] parsePgVectorText(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        String[] parts = s.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Double.parseDouble(parts[i].trim());
        }
        return v;
    }

    private static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double normalize(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        if (max <= min) {
            return v > 0 ? 1.0 : 0.0;
        }
        return (v - min) / (max - min);
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, x));
    }

    public List<ScoredChunk> hybridSearch(String fileId, String query) {
        long t0 = System.nanoTime();

        CompletableFuture<List<ScoredChunk>> vecF = CompletableFuture.supplyAsync(
                () -> vectorSearchService.vectorSearch(fileId, query, topK),
                executor
        );

        CompletableFuture<List<ElasticsearchService.EsHit>> bm25F = CompletableFuture.supplyAsync(
                () -> elasticsearchService.bm25Search(fileId, query, topK),
                executor
        );

        List<ScoredChunk> vec = vecF.join();
        List<ElasticsearchService.EsHit> bm25 = bm25F.join();

        Map<UUID, ScoredChunk> merged = new HashMap<>();

        for (ScoredChunk sc : vec) {
            merged.put(sc.id(), sc);
        }
        for (ElasticsearchService.EsHit hit : bm25) {
            ScoredChunk existing = merged.get(hit.id());
            if (existing == null) {
                merged.put(hit.id(), new ScoredChunk(
                        hit.id(),
                        hit.fileId(),
                        hit.content(),
                        hit.metadata(),
                        0.0,
                        hit.bm25Score(),
                        0.0
                ));
            } else {
                merged.put(hit.id(), new ScoredChunk(
                        existing.id(),
                        existing.fileId(),
                        existing.content(),
                        existing.metadata(),
                        existing.vectorScore(),
                        hit.bm25Score(),
                        existing.fusedScore()
                ));
            }
        }

        List<ScoredChunk> fused = fuseScores(new ArrayList<>(merged.values()));

        // MMR diversification
        List<ScoredChunk> diversified = mmrDiversify(fused, mmrK, mmrLambda);

        // "Rerank" (lightweight): final sort by fused score then small lexical overlap bonus
        List<ScoredChunk> reranked = rerank(diversified, query);

        long t1 = System.nanoTime();
        log.info("event=hybrid_search_done fileId={} topK={} alpha={} vecN={} bm25N={} mergedN={} outN={} ms={}",
                fileId, topK, alpha, vec.size(), bm25.size(), fused.size(), reranked.size(),
                (t1 - t0) / 1_000_000);

        return reranked;
    }

    private List<ScoredChunk> fuseScores(List<ScoredChunk> items) {
        // Normalize vector scores and bm25 scores to [0,1] per-query.
        DoubleSummaryStatistics vStats = items.stream().mapToDouble(ScoredChunk::vectorScore).summaryStatistics();
        DoubleSummaryStatistics bStats = items.stream().mapToDouble(ScoredChunk::bm25Score).summaryStatistics();

        double vMin = vStats.getMin();
        double vMax = vStats.getMax();
        double bMin = bStats.getMin();
        double bMax = bStats.getMax();

        List<ScoredChunk> out = new ArrayList<>(items.size());
        for (ScoredChunk sc : items) {
            double vNorm = normalize(sc.vectorScore(), vMin, vMax);
            double bNorm = normalize(sc.bm25Score(), bMin, bMax);
            double fused = alpha * vNorm + (1.0 - alpha) * bNorm;
            out.add(new ScoredChunk(
                    sc.id(),
                    sc.fileId(),
                    sc.content(),
                    sc.metadata(),
                    sc.vectorScore(),
                    sc.bm25Score(),
                    fused
            ));
        }

        out.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed());
        return out;
    }

    private List<ScoredChunk> mmrDiversify(List<ScoredChunk> ranked, int k, double lambda) {
        if (ranked.isEmpty()) {
            return ranked;
        }

        int candidateCap = Math.min(ranked.size(), Math.max(k * 4, 20));
        List<ScoredChunk> candidates = ranked.subList(0, candidateCap);

        Map<UUID, double[]> embeddings = fetchEmbeddings(
                candidates.stream().map(ScoredChunk::id).collect(Collectors.toList())
        );

        List<ScoredChunk> selected = new ArrayList<>(k);
        HashSet<UUID> selectedIds = new HashSet<>();

        // Start with best fused
        for (ScoredChunk sc : candidates) {
            if (embeddings.containsKey(sc.id())) {
                selected.add(sc);
                selectedIds.add(sc.id());
                break;
            }
        }

        while (selected.size() < Math.min(k, candidates.size())) {
            ScoredChunk best = null;
            double bestScore = -Double.MAX_VALUE;

            for (ScoredChunk c : candidates) {
                if (selectedIds.contains(c.id())) {
                    continue;
                }
                double[] ce = embeddings.get(c.id());
                if (ce == null) {
                    continue;
                }

                double maxSimToSelected = 0.0;
                for (ScoredChunk s : selected) {
                    double[] se = embeddings.get(s.id());
                    if (se == null) {
                        continue;
                    }
                    maxSimToSelected = Math.max(maxSimToSelected, cosine(ce, se));
                }

                // relevance: fusedScore, diversity: penalize similarity to selected
                double mmr = lambda * c.fusedScore() - (1.0 - lambda) * maxSimToSelected;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = c;
                }
            }

            if (best == null) {
                break;
            }
            selected.add(best);
            selectedIds.add(best.id());
        }

        // Keep original order preference by fusedScore among selected, stable
        selected.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed());
        return selected;
    }

    private List<ScoredChunk> rerank(List<ScoredChunk> items, String query) {
        if (items.isEmpty()) {
            return items;
        }
        String q = query == null ? "" : query.toLowerCase();
        List<String> qTerms = List.of(q.split("\\s+"));

        List<ScoredChunk> out = new ArrayList<>(items.size());
        for (ScoredChunk sc : items) {
            String c = sc.content() == null ? "" : sc.content().toLowerCase();
            int hits = 0;
            for (String t : qTerms) {
                if (t.length() >= 3 && c.contains(t)) {
                    hits++;
                }
            }
            double bonus = Math.min(0.10, hits * 0.02); // tiny bonus, bounded
            out.add(new ScoredChunk(
                    sc.id(), sc.fileId(), sc.content(), sc.metadata(),
                    sc.vectorScore(), sc.bm25Score(), sc.fusedScore() + bonus
            ));
        }
        out.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed());
        return out;
    }

    private Map<UUID, double[]> fetchEmbeddings(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, double[]> out = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            Array uuidArray = conn.createArrayOf("uuid", ids.toArray());
            try (var ps = conn.prepareStatement("SELECT id, embedding::text AS embedding_text FROM vector_store WHERE id = ANY(?)")) {
                ps.setArray(1, uuidArray);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = (UUID) rs.getObject("id");
                        String v = rs.getString("embedding_text");
                        double[] parsed = parsePgVectorText(v);
                        if (parsed != null) {
                            out.put(id, parsed);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch embeddings for MMR", e);
        }

        return out;
    }
}