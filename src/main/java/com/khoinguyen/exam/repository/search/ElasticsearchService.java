package com.khoinguyen.exam.repository.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ElasticsearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchService.class);

    private final ElasticsearchClient client;
    private final String indexName;

    public ElasticsearchService(
            ElasticsearchClient client,
            @Value("${exam.elasticsearch.index}") String indexName
    ) {
        this.client = client;
        this.indexName = indexName;
    }

    public static Map<String, Object> esDoc(UUID id, String fileId, String content, Map<String, Object> metadata) {
        return Map.of(
                "id", id.toString(),
                "fileId", fileId,
                "content", content,
                "metadata", metadata == null ? Map.of() : metadata,
                "createdAt", Instant.now().toString()
        );
    }

    public void ensureIndexExists() {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                return;
            }
            client.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m
                            .properties("id", p -> p.keyword(k -> k))
                            .properties("fileId", p -> p.keyword(k -> k))
                            .properties("content", p -> p.text(t -> t))
                            .properties("metadata", p -> p.object(o -> o.enabled(true)))
                            .properties("createdAt", p -> p.date(d -> d))
                    )
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                    )
            );
            log.info("event=es_index_created index={}", indexName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to ensure Elasticsearch index exists: " + indexName, e);
        }
    }

    public void bulkIndex(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        ensureIndexExists();

        List<BulkOperation> ops = new ArrayList<>(docs.size());
        for (Map<String, Object> doc : docs) {
            Object id = doc.get("id");
            if (id == null) {
                throw new IllegalArgumentException("Elasticsearch bulk document missing 'id'");
            }
            ops.add(BulkOperation.of(b -> b
                    .index(i -> i
                            .index(indexName)
                            .id(String.valueOf(id))
                            .document(doc)
                    )));
        }

        try {
            BulkRequest request = BulkRequest.of(b -> b.operations(ops));
            BulkResponse resp = client.bulk(request);

            if (resp.errors()) {
                resp.items().forEach(item -> {
                    if (item.error() != null) {
                        log.error("event=es_bulk_item_error id={} reason={}",
                                item.id(),
                                item.error().reason());
                    }
                });

                log.warn("event=es_bulk_index_errors index={} took={}ms errors=true",
                        indexName, resp.took());
            } else {
                log.info("event=es_bulk_index_ok index={} count={} took={}ms",
                        indexName, docs.size(), resp.took());
            }
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch bulk index failed", e);
        }
    }

    public List<EsHit> bm25Search(String fileId, String queryText, int topK) {
        ensureIndexExists();
        try {
            Query termFilter = TermQuery.of(t -> t.field("fileId").value(fileId))._toQuery();
            Query match = MatchQuery.of(m -> m.field("content").query(queryText))._toQuery();

            BoolQuery bool = BoolQuery.of(b -> b
                    .filter(termFilter)
                    .must(match)
            );

            SearchResponse<Map> response = client.search(s -> s
                            .index(indexName)
                            .query(bool._toQuery())
                            .size(topK)
                            .sort(so -> so.score(sc -> sc.order(SortOrder.Desc))),
                    Map.class
            );

            List<EsHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map src = hit.source();
                if (src == null) {
                    continue;
                }
                UUID id = UUID.fromString(String.valueOf(src.get("id")));
                String fid = String.valueOf(src.get("fileId"));
                String content = String.valueOf(src.get("content"));
                Map<String, Object> metadata = (Map<String, Object>) src.getOrDefault("metadata", Map.of());
                double score = hit.score() == null ? 0.0 : hit.score();
                hits.add(new EsHit(id, fid, content, metadata, score));
            }

            log.info("event=es_bm25_search fileId={} topK={} returned={}",
                    fileId, topK, hits.size());
            return hits;
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch BM25 search failed", e);
        }
    }

    public record EsHit(UUID id, String fileId, String content, Map<String, Object> metadata, double bm25Score) {
    }
}