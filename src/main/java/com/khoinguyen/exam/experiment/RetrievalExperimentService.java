package com.khoinguyen.exam.experiment;

import com.khoinguyen.exam.repository.search.ElasticsearchService;
import com.khoinguyen.exam.repository.vector.VectorSearchService;
import com.khoinguyen.exam.service.ScoredChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetrievalExperimentService {

    private static final int RRF_K = 60;

    private final VectorSearchService vectorSearchService;
    private final ElasticsearchService elasticsearchService;

    public List<ScoredChunk> retrieve(
        String fileId,
        String query,
        int topK,
        RetrievalMode mode,
        double alpha,
        boolean useMmr,
        int mmrK,
        double mmrLambda
    ) {
        int fetchK = topK * 3; // 🔥 quan trọng

        List<ScoredChunk> vec = Collections.emptyList();
        List<ElasticsearchService.EsHit> bm25 = Collections.emptyList();

        switch (mode) {
            case DENSE_ONLY -> vec = vectorSearchService.vectorSearch(fileId, query, fetchK);
            case BM25_ONLY -> bm25 = elasticsearchService.bm25Search(fileId, query, fetchK);
            case HYBRID_ALPHA_FUSION -> {
                vec = vectorSearchService.vectorSearch(fileId, query, fetchK);
                bm25 = elasticsearchService.bm25Search(fileId, query, fetchK);
            }
        }

        List<ScoredChunk> ranked;

        switch (mode) {
            case DENSE_ONLY -> ranked = sortDense(vec);
            case BM25_ONLY -> ranked = sortBm25(bm25);
            case HYBRID_ALPHA_FUSION -> ranked = weightedRrf(vec, bm25, alpha);
            default -> ranked = List.of();
        }

        if (useMmr) {
            ranked = mmrDiversify(ranked, mmrK, mmrLambda);
        }

        return ranked.size() > topK ? ranked.subList(0, topK) : ranked;
    }

    // ===== DENSE =====
    private List<ScoredChunk> sortDense(List<ScoredChunk> vec) {
        return vec.stream()
            .sorted(Comparator.comparingDouble(ScoredChunk::vectorScore).reversed())
            .collect(Collectors.toList());
    }

    // ===== BM25 =====
    private List<ScoredChunk> sortBm25(List<ElasticsearchService.EsHit> bm25) {
        return bm25.stream()
            .map(hit -> new ScoredChunk(
                hit.id(),
                hit.fileId(),
                hit.content(),
                hit.metadata(),
                0.0,
                hit.bm25Score(),
                hit.bm25Score()
            ))
            .sorted(Comparator.comparingDouble(ScoredChunk::bm25Score).reversed())
            .collect(Collectors.toList());
    }

    // ===== 🔥 HYBRID (FIX CHÍNH) =====
    private List<ScoredChunk> weightedRrf(
        List<ScoredChunk> vec,
        List<ElasticsearchService.EsHit> bm25,
        double alpha
    ) {
        double denseWeight = Math.max(0.0, Math.min(1.0, alpha));
        double bm25Weight = 1.0 - denseWeight;

        Map<UUID, ScoredChunk> merged = new HashMap<>();
        Map<UUID, Double> fusedScore = new HashMap<>();

        // Dense
        for (int rank = 0; rank < vec.size(); rank++) {
            ScoredChunk sc = vec.get(rank);
            merged.put(sc.id(), sc);

            double score = denseWeight * (1.0 / (RRF_K + rank + 1));
            fusedScore.merge(sc.id(), score, Double::sum);
        }

        // BM25
        for (int rank = 0; rank < bm25.size(); rank++) {
            var hit = bm25.get(rank);

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

            double score = bm25Weight * (1.0 / (RRF_K + rank + 1));
            fusedScore.merge(hit.id(), score, Double::sum);
        }

        List<ScoredChunk> out = new ArrayList<>();

        for (var e : merged.entrySet()) {
            ScoredChunk sc = e.getValue();
            double fused = fusedScore.getOrDefault(e.getKey(), 0.0);

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

        out.sort(
            Comparator.comparingDouble(ScoredChunk::fusedScore).reversed()
                .thenComparingDouble(ScoredChunk::bm25Score).reversed()
                .thenComparingDouble(ScoredChunk::vectorScore).reversed()
        );

        return out;
    }

    // ===== MMR =====
    private List<ScoredChunk> mmrDiversify(List<ScoredChunk> ranked, int k, double lambda) {
        if (ranked.isEmpty()) return ranked;

        List<ScoredChunk> selected = new ArrayList<>();
        selected.add(ranked.get(0));

        while (selected.size() < Math.min(k, ranked.size())) {
            ScoredChunk best = null;
            double bestScore = -Double.MAX_VALUE;

            for (ScoredChunk cand : ranked) {
                if (selected.contains(cand)) continue;

                double relevance = cand.fusedScore();

                double maxSim = 0;
                for (ScoredChunk s : selected) {
                    maxSim = Math.max(maxSim, similarity(cand, s));
                }

                double score = lambda * relevance - (1 - lambda) * maxSim;

                if (score > bestScore) {
                    bestScore = score;
                    best = cand;
                }
            }

            if (best == null) break;
            selected.add(best);
        }

        return selected;
    }

    private double similarity(ScoredChunk a, ScoredChunk b) {
        return a.content().equalsIgnoreCase(b.content()) ? 1.0 : 0.0;
    }
}