package com.khoinguyen.exam.experiment;

import com.khoinguyen.exam.repository.search.ElasticsearchService;
import com.khoinguyen.exam.repository.vector.VectorSearchService;
import com.khoinguyen.exam.service.ScoredChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetrievalExperimentService {

    private final VectorSearchService vectorSearchService;
    private final ElasticsearchService elasticsearchService;
    private final DataSource dataSource;

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
        List<ScoredChunk> vec = Collections.emptyList();
        List<ElasticsearchService.EsHit> bm25 = Collections.emptyList();

        switch (mode) {
            case DENSE_ONLY -> vec = vectorSearchService.vectorSearch(fileId, query, topK);
            case BM25_ONLY -> bm25 = elasticsearchService.bm25Search(fileId, query, topK);
            case HYBRID_ALPHA_FUSION -> {
                vec = vectorSearchService.vectorSearch(fileId, query, topK);
                bm25 = elasticsearchService.bm25Search(fileId, query, topK);
            }
        }

        // merge
        Map<UUID, ScoredChunk> merged = new HashMap<>();
        for (ScoredChunk sc : vec) merged.put(sc.id(), sc);

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

        List<ScoredChunk> fused = fuseScores(new ArrayList<>(merged.values()), alpha);

        List<ScoredChunk> out = fused;
        if (useMmr) out = mmrDiversify(fused, mmrK, mmrLambda);

        // final sort
        out.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed());

        return out.size() > topK ? out.subList(0, topK) : out;
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static double normalize(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (max <= min) return v > 0 ? 1.0 : 0.0;
        return (v - min) / (max - min);
    }

    private List<ScoredChunk> fuseScores(List<ScoredChunk> items, double alpha) {
        double a = clamp01(alpha);

        DoubleSummaryStatistics vStats = items.stream().mapToDouble(ScoredChunk::vectorScore).summaryStatistics();
        DoubleSummaryStatistics bStats = items.stream().mapToDouble(ScoredChunk::bm25Score).summaryStatistics();

        double vMin = vStats.getMin(), vMax = vStats.getMax();
        double bMin = bStats.getMin(), bMax = bStats.getMax();

        List<ScoredChunk> out = new ArrayList<>(items.size());
        for (ScoredChunk sc : items) {
            double vNorm = normalize(sc.vectorScore(), vMin, vMax);
            double bNorm = normalize(sc.bm25Score(), bMin, bMax);
            double fused = a * vNorm + (1.0 - a) * bNorm;

            out.add(new ScoredChunk(
                    sc.id(), sc.fileId(), sc.content(), sc.metadata(),
                    sc.vectorScore(), sc.bm25Score(), fused
            ));
        }
        out.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed());
        return out;
    }

    // ---- MMR (dùng embedding lấy từ bảng document_chunks nếu bạn đang lưu) ----
    private List<ScoredChunk> mmrDiversify(List<ScoredChunk> ranked, int k, double lambda) {
        if (ranked.isEmpty()) return ranked;

        int kk = Math.max(1, k);
        double lam = clamp01(lambda);

        int cap = Math.min(ranked.size(), Math.max(kk * 4, 20));
        List<ScoredChunk> candidates = ranked.subList(0, cap);

        Map<UUID, double[]> embeddings = fetchEmbeddings(
                candidates.stream().map(ScoredChunk::id).collect(Collectors.toList())
        );

        List<ScoredChunk> selected = new ArrayList<>(kk);
        Set<UUID> selectedIds = new HashSet<>();

        // start with best fused that has embedding
        for (ScoredChunk sc : candidates) {
            if (embeddings.containsKey(sc.id())) {
                selected.add(sc);
                selectedIds.add(sc.id());
                break;
            }
        }
        if (selected.isEmpty()) return ranked; // fallback

        while (selected.size() < Math.min(kk, candidates.size())) {
            ScoredChunk best = null;
            double bestScore = -1e9;

            for (ScoredChunk cand : candidates) {
                if (selectedIds.contains(cand.id())) continue;
                double[] cEmb = embeddings.get(cand.id());
                if (cEmb == null) continue;

                double simToQuery = cand.fusedScore(); // proxy
                double maxSimToSelected = 0.0;

                for (ScoredChunk s : selected) {
                    double[] sEmb = embeddings.get(s.id());
                    if (sEmb == null) continue;
                    double sim = cosine(cEmb, sEmb);
                    if (sim > maxSimToSelected) maxSimToSelected = sim;
                }

                double mmr = lam * simToQuery - (1.0 - lam) * maxSimToSelected;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = cand;
                }
            }

            if (best == null) break;
            selected.add(best);
            selectedIds.add(best.id());
        }

        return selected;
    }

    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * IMPORTANT: query này phụ thuộc schema DB của bạn.
     * Repo hiện có model DocumentChunk và bạn đang dùng pgvector.
     *
     * Nếu bảng bạn tên khác, đổi SQL tại đây.
     * Mặc định giả định:
     *  - table: document_chunks
     *  - columns: id (uuid), embedding (vector) hoặc embedding_text (text)
     */
    private Map<UUID, double[]> fetchEmbeddings(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();

        // thử 2 kiểu: embedding dạng text "[..]" hoặc vector -> cast text
        String sql = "select id, embedding::text as emb_text from document_chunks where id = any (?)";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            Array arr = c.createArrayOf("uuid", ids.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                Map<UUID, double[]> out = new HashMap<>();
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    String txt = rs.getString("emb_text");
                    double[] v = parsePgVectorText(txt);
                    if (v != null) out.put(id, v);
                }
                return out;
            }
        } catch (Exception e) {
            // Nếu schema khác => MMR fallback sẽ dùng ranked list
            return Map.of();
        }
    }

    // pgvector text: [0.1,0.2,...]
    private static double[] parsePgVectorText(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.isEmpty()) return null;

        String[] parts = s.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Double.parseDouble(parts[i].trim());
        return v;
    }
}