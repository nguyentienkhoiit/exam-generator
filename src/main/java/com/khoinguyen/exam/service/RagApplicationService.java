package com.khoinguyen.exam.service;

import com.khoinguyen.exam.dto.ExamDraftRequest;
import com.khoinguyen.exam.dto.ExamDraftResponse;
import com.khoinguyen.exam.service.ingest.IngestService;
import com.khoinguyen.exam.service.llm.ExamDraftPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RagApplicationService.class);

    private final IngestService ingestService;
    private final HybridSearchService hybridSearchService;
    private final ExamDraftPromptService examDraftPromptService;

    private final int maxContextChars;
    private final int maxContextChunks;

    public RagApplicationService(
            IngestService ingestService,
            HybridSearchService hybridSearchService,
            ExamDraftPromptService examDraftPromptService,
            @Value("${exam.rag.context.max-chars}") int maxContextChars,
            @Value("${exam.rag.context.max-chunks}") int maxContextChunks
    ) {
        this.ingestService = ingestService;
        this.hybridSearchService = hybridSearchService;
        this.examDraftPromptService = examDraftPromptService;
        this.maxContextChars = Math.max(500, maxContextChars);
        this.maxContextChunks = Math.max(1, maxContextChunks);
    }

    private static String buildContext(List<ScoredChunk> chunks, int maxChunks, int maxChars) {
        if (chunks == null || chunks.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        int used = 0;

        for (ScoredChunk sc : chunks) {
            if (parts.size() >= maxChunks) break;

            String c = sc.content() == null ? "" : sc.content().trim();
            if (c.isEmpty()) continue;

            // reduce tokens
            String trimmed = c.length() > 2000 ? c.substring(0, 2000) : c;

            String block = "[chunkId=" + sc.id()
                    + " fused=" + String.format("%.4f", sc.fusedScore())
                    + "]\n" + trimmed;

            if (used + block.length() + 2 > maxChars) {
                int remaining = maxChars - used - 2;
                if (remaining > 200) {
                    parts.add(block.substring(0, Math.min(block.length(), remaining)));
                }
                break;
            }

            parts.add(block);
            used += block.length() + 2;
        }

        return String.join("\n\n", parts);
    }

    /**
     * Pipeline:
     * Ingest -> Hybrid Search -> Build context -> LLM (Spring AI ChatClient) -> ExamDraftResponse
     */
    public ExamDraftResponse ask(ExamDraftRequest request, MultipartFile file) {
        long t0 = System.nanoTime();

        IngestService.IngestResult ingest = ingestService.ingest(request, file);
        String fileId = ingest.fileId();

        // Query = topic (per current contract)
        List<ScoredChunk> retrieved = hybridSearchService.hybridSearch(fileId, request.getTopic());
        String context = buildContext(retrieved, maxContextChunks, maxContextChars);

        ExamDraftResponse response = examDraftPromptService.generateExamDraft(request, context);

        long t1 = System.nanoTime();
        int qCount = (response == null || response.getQuestions() == null) ? 0 : response.getQuestions().size();
        log.info("event=rag_ask_done fileId={} chunks_ingested={} retrieved={} context_chars={} questions={} ms={}",
                fileId,
                ingest.chunks(),
                retrieved.size(),
                context.length(),
                qCount,
                (t1 - t0) / 1_000_000);

        return response;
    }
}