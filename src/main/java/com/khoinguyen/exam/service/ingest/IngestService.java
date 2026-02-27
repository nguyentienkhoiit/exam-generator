package com.khoinguyen.exam.service.ingest;

import com.khoinguyen.exam.dto.ExamDraftRequest;
import com.khoinguyen.exam.repository.ingest.IngestCacheRepository;
import com.khoinguyen.exam.repository.search.ElasticsearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final PdfExtractor pdfExtractor;
    private final TokenTextChunker chunker;
    private final VectorStore vectorStore;
    private final ElasticsearchService elasticsearchService;

    private final IngestCacheRepository ingestCacheRepository;
    private final boolean ingestCacheEnabled;

    public IngestService(
            PdfExtractor pdfExtractor,
            TokenTextChunker chunker,
            @Qualifier("ollamaVectorStore") VectorStore vectorStore,
            ElasticsearchService elasticsearchService,
            IngestCacheRepository ingestCacheRepository,
            @Value("${exam.ingest.cache.enabled:true}") boolean ingestCacheEnabled
    ) {
        this.pdfExtractor = pdfExtractor;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.elasticsearchService = elasticsearchService;
        this.ingestCacheRepository = ingestCacheRepository;
        this.ingestCacheEnabled = ingestCacheEnabled;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit((b & 0xF), 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Offline ingest pipeline (performed synchronously per request here):
     * PDF -> token chunking -> embeddings (Ollama) -> pgvector (PgVectorStore) -> raw text -> Elasticsearch bulk (BM25)
     */
    public IngestResult ingest(ExamDraftRequest request, MultipartFile pdf) {
        byte[] bytes;
        try {
            bytes = pdf.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF upload bytes", e);
        }

        String sha256 = sha256Hex(bytes);
        if (ingestCacheEnabled) {
            var cached = ingestCacheRepository.findBySha256(sha256);
            if (cached.isPresent()) {
                var c = cached.get();
                log.info("event=ingest_cache_hit sha256={} fileId={} chunks={}", sha256, c.fileId(), c.chunks());
                return new IngestResult(c.fileId(), c.chunks());
            }
        }

        String fileId = UUID.randomUUID().toString();

        String extracted;
        try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
            extracted = pdfExtractor.extractText(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF upload", e);
        }

        List<String> chunks = chunker.chunk(extracted);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Uploaded PDF has no extractable text after chunking.");
        }

        List<Document> docsForVector = new ArrayList<>(chunks.size());
        List<Map<String, Object>> docsForEs = new ArrayList<>(chunks.size());

        Instant now = Instant.now();
        String sourceName = (pdf.getOriginalFilename() == null ? "upload.pdf" : pdf.getOriginalFilename());

        for (int i = 0; i < chunks.size(); i++) {
            UUID id = UUID.randomUUID();
            String content = chunks.get(i);

            Map<String, Object> md = new HashMap<>();
            md.put("fileId", fileId);
            md.put("topic", request.getTopic());
            md.put("chunkIndex", i);
            md.put("source", sourceName);
            md.put("createdAt", now.toString());

            Document doc = new Document(id.toString(), content, md);
            docsForVector.add(doc);

            docsForEs.add(ElasticsearchService.esDoc(id, fileId, content, md));
        }

        long t0 = System.nanoTime();
        vectorStore.add(docsForVector);
        long t1 = System.nanoTime();
        elasticsearchService.bulkIndex(docsForEs);
        long t2 = System.nanoTime();

        log.info("event=ingest_complete fileId={} chunks={} pg_ms={} es_ms={}",
                fileId,
                chunks.size(),
                (t1 - t0) / 1_000_000,
                (t2 - t1) / 1_000_000
        );

        if (ingestCacheEnabled) {
            ingestCacheRepository.upsert(sha256, fileId, chunks.size(), sourceName, bytes.length);
        }

        return new IngestResult(fileId, chunks.size());
    }

    public record IngestResult(String fileId, int chunks) {
    }
}