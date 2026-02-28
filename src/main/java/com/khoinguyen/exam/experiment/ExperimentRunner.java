package com.khoinguyen.exam.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khoinguyen.exam.dto.ExamDraftRequest;
import com.khoinguyen.exam.dto.ExamDraftResponse;
import com.khoinguyen.exam.service.RagApplicationService;
import com.khoinguyen.exam.service.ScoredChunk;
import com.khoinguyen.exam.service.ingest.IngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("experiments")
@RequiredArgsConstructor
public class ExperimentRunner implements ApplicationRunner {

    private final ObjectMapper objectMapper;

    private final IngestService ingestService;
    private final RetrievalExperimentService retrievalExperimentService;
    private final RagApplicationService ragApplicationService;

    // --- config defaults (theo thesis / code) ---
    private final String outDir = "./exp-out";

    private final int chunkTokens = 600;
    private final int overlapTokens = 80;

    private final int topK = 12;
    private final double alpha = 0.6;
    private final int mmrK = 8;
    private final double mmrLambda = 0.7;

    private final int genRuns = 8;

    @Value("${experiments.runExp1:true}") private boolean runExp1;
    @Value("${experiments.runExp2:true}") private boolean runExp2;
    @Value("${experiments.runExp3:true}") private boolean runExp3;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        new File(outDir).mkdirs();

        List<TaskSpec> tasks = loadTasks("bench/tasks.json");
        Map<String, Set<UUID>> annotations = loadAnnotationsOrEmpty("bench/annotations.json");

        log.info("Loaded tasks: {}", tasks.size());
        log.info("Loaded annotations: {}", annotations.size());

        if (runExp1) runExp1(tasks);
        if (runExp2) runExp2(tasks, annotations);
        if (runExp3) runExp3(tasks);

        log.info("DONE. Output in {}", outDir);
    }

    // ================== EXP 1 ==================
    private void runExp1(List<TaskSpec> tasks) throws Exception {
        File csv = new File(outDir, "exp1_chunking.csv");
        try (PrintWriter pw = writer(csv)) {
            pw.println("taskId,pdfClasspath,fileId,numChunks,avgChars,approxRedundancyRatio");

            for (TaskSpec t : tasks) {
                byte[] pdfBytes = readClasspathBytes(t.pdfClasspath());
                ByteArrayMultipartFile mf = new ByteArrayMultipartFile(
                        pdfBytes, "file", guessName(t.pdfClasspath()), "application/pdf"
                );

                ExamDraftRequest req = toExamDraftRequest(t.prompt());
                IngestService.IngestResult ing = ingestService.ingest(req, mf);

                // avgChars: lấy từ cache DB sẽ phức tạp; đo gần đúng bằng cách chia tổng chars / chunks
                // (đủ cho bảng mô tả). Nếu muốn chính xác per-chunk, bạn cần expose chunks trong ingest.
                double approxAvgChars = estimateAvgCharsFromPdf(pdfBytes, ing.chunks());

                double approxRed = (chunkTokens == 0) ? 0 : (overlapTokens * 1.0 / chunkTokens);

                pw.printf(Locale.US, "%s,%s,%s,%d,%.2f,%.4f%n",
                        t.id(), t.pdfClasspath(), ing.fileId(), ing.chunks(), approxAvgChars, approxRed);
            }
        }
        log.info("Exp1 -> {}", csv.getAbsolutePath());
    }

    // ================== EXP 2 ==================
    private void runExp2(List<TaskSpec> tasks, Map<String, Set<UUID>> annotations) throws Exception {
        File csv = new File(outDir, "exp2_retrieval.csv");
        try (PrintWriter pw = writer(csv)) {
            pw.println("taskId,mode,recallAt12,redundantCount");

            boolean hasGt = !annotations.isEmpty();

            for (TaskSpec t : tasks) {
                byte[] pdfBytes = readClasspathBytes(t.pdfClasspath());
                ByteArrayMultipartFile mf = new ByteArrayMultipartFile(
                        pdfBytes, "file", guessName(t.pdfClasspath()), "application/pdf"
                );

                ExamDraftRequest req = toExamDraftRequest(t.prompt());
                IngestService.IngestResult ing = ingestService.ingest(req, mf);

                String fileId = ing.fileId();
                String query = req.getTopic(); // contract hiện tại: query=topic

                // 4 mode giống thesis
                writeRetrievalRow(pw, t, "DENSE_ONLY", hasGt, annotations,
                        retrievalExperimentService.retrieve(fileId, query, topK, RetrievalMode.DENSE_ONLY, alpha, false, mmrK, mmrLambda));

                writeRetrievalRow(pw, t, "BM25_ONLY", hasGt, annotations,
                        retrievalExperimentService.retrieve(fileId, query, topK, RetrievalMode.BM25_ONLY, alpha, false, mmrK, mmrLambda));

                writeRetrievalRow(pw, t, "HYBRID_ALPHA", hasGt, annotations,
                        retrievalExperimentService.retrieve(fileId, query, topK, RetrievalMode.HYBRID_ALPHA_FUSION, alpha, false, mmrK, mmrLambda));

                writeRetrievalRow(pw, t, "HYBRID_ALPHA_MMR", hasGt, annotations,
                        retrievalExperimentService.retrieve(fileId, query, topK, RetrievalMode.HYBRID_ALPHA_FUSION, alpha, true, mmrK, mmrLambda));
            }
        }
        log.info("Exp2 -> {}", csv.getAbsolutePath());
    }

    private void writeRetrievalRow(
            PrintWriter pw,
            TaskSpec t,
            String modeName,
            boolean hasGt,
            Map<String, Set<UUID>> annotations,
            List<ScoredChunk> retrieved
    ) {
        Set<UUID> retIds = retrieved.stream().map(ScoredChunk::id).collect(Collectors.toSet());

        double recall = -1.0;
        if (hasGt && annotations.containsKey(t.id())) {
            Set<UUID> gt = annotations.get(t.id());
            long hit = gt.stream().filter(retIds::contains).count();
            recall = gt.isEmpty() ? 0.0 : (hit * 1.0 / gt.size());
        }

        int redundant = countRedundantByNormalizedText(retrieved);

        pw.printf(Locale.US, "%s,%s,%.4f,%d%n", t.id(), modeName, recall, redundant);
    }

    // redundancy: đếm duplicate dựa trên normalize(question/context) (đơn giản, chạy ổn)
    private int countRedundantByNormalizedText(List<ScoredChunk> chunks) {
        Set<String> seen = new HashSet<>();
        int dup = 0;
        for (ScoredChunk sc : chunks) {
            String norm = normalize(sc.content());
            if (!seen.add(norm)) dup++;
        }
        return dup;
    }

    // ================== EXP 3 ==================
    private void runExp3(List<TaskSpec> tasks) throws Exception {
        File csvRuns = new File(outDir, "exp3_runs.csv");
        File csvAgg  = new File(outDir, "exp3_summary.csv");

        List<Long> allLatMs = new ArrayList<>();
        int totalRuns = 0;
        int validJsonRuns = 0;
        int csrRuns = 0;

        try (PrintWriter pw = writer(csvRuns)) {
            pw.println("taskId,runIdx,latencyMs,jsonValid,csr,dupRate,qCount,mcqCount,tfCount,saCount");

            for (TaskSpec t : tasks) {
                byte[] pdfBytes = readClasspathBytes(t.pdfClasspath());
                ByteArrayMultipartFile mf = new ByteArrayMultipartFile(
                        pdfBytes, "file", guessName(t.pdfClasspath()), "application/pdf"
                );

                ExamDraftRequest req = toExamDraftRequest(t.prompt());

                for (int r = 1; r <= genRuns; r++) {
                    totalRuns++;

                    long t0 = System.nanoTime();
                    boolean jsonValid = true;
                    ExamDraftResponse resp = null;

                    try {
                        resp = ragApplicationService.ask(req, mf);
                    } catch (Exception e) {
                        jsonValid = false;
                    }
                    long t1 = System.nanoTime();
                    long ms = (t1 - t0) / 1_000_000;

                    allLatMs.add(ms);

                    int qCount = (resp == null || resp.getQuestions() == null) ? 0 : resp.getQuestions().size();
                    int mcq = countType(resp, "multiple");
                    int tf  = countType(resp, "true");
                    int sa  = countType(resp, "short");

                    double dupRate = duplicationRate(resp);

                    boolean csr = checkConstraints(t.prompt(), qCount, mcq, tf, sa);

                    if (jsonValid) validJsonRuns++;
                    if (jsonValid && csr) csrRuns++;

                    pw.printf(Locale.US, "%s,%d,%d,%d,%d,%.4f,%d,%d,%d,%d%n",
                            t.id(), r, ms,
                            jsonValid ? 1 : 0,
                            csr ? 1 : 0,
                            dupRate,
                            qCount, mcq, tf, sa
                    );
                }
            }
        }

        // summary
        double jsonValidRate = totalRuns == 0 ? 0 : (validJsonRuns * 1.0 / totalRuns);
        double csrRate = totalRuns == 0 ? 0 : (csrRuns * 1.0 / totalRuns);

        long p50 = percentile(allLatMs, 50);
        long p95 = percentile(allLatMs, 95);

        try (PrintWriter pw = writer(csvAgg)) {
            pw.println("totalRuns,jsonValidRate,csrRate,latencyP50Ms,latencyP95Ms");
            pw.printf(Locale.US, "%d,%.4f,%.4f,%d,%d%n", totalRuns, jsonValidRate, csrRate, p50, p95);
        }

        log.info("Exp3 -> {}, {}", csvRuns.getAbsolutePath(), csvAgg.getAbsolutePath());
    }

    private int countType(ExamDraftResponse resp, String key) {
        if (resp == null || resp.getQuestions() == null) return 0;
        int c = 0;
        for (ExamDraftResponse.QuestionDraftResponse q : resp.getQuestions()) {
            String t = q.getQuestion_type();
            if (t == null) continue;
            String s = t.toLowerCase(Locale.ROOT);
            if (s.contains(key)) c++;
        }
        return c;
    }

    private double duplicationRate(ExamDraftResponse resp) {
        if (resp == null || resp.getQuestions() == null || resp.getQuestions().isEmpty()) return 0.0;
        List<String> qs = resp.getQuestions().stream()
                .map(ExamDraftResponse.QuestionDraftResponse::getQuestion_text)
                .filter(Objects::nonNull)
                .map(this::normalize)
                .toList();

        int total = qs.size();
        int unique = new HashSet<>(qs).size();
        return total == 0 ? 0.0 : ((total - unique) * 1.0 / total);
    }

    private boolean checkConstraints(PromptSpec p, int total, int mcq, int tf, int sa) {
        int wantTotal = p.numQuestions() == null ? 10 : p.numQuestions();
        ConstraintSpec c = p.constraints();
        if (c == null) return total == wantTotal;

        int wantMcq = c.mcq() == null ? 6 : c.mcq();
        int wantTf  = c.trueFalse() == null ? 2 : c.trueFalse();
        int wantSa  = c.shortAnswer() == null ? 2 : c.shortAnswer();

        return total == wantTotal && mcq == wantMcq && tf == wantTf && sa == wantSa;
    }

    private long percentile(List<Long> values, int pct) {
        if (values == null || values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil((pct / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    // ================== IO/UTIL ==================
    private List<TaskSpec> loadTasks(String classpath) throws IOException {
        ClassPathResource r = new ClassPathResource(classpath);
        try (InputStream is = r.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<TaskSpec>>() {});
        }
    }

    private Map<String, Set<UUID>> loadAnnotationsOrEmpty(String classpath) {
        try {
            ClassPathResource r = new ClassPathResource(classpath);
            if (!r.exists()) return Map.of();

            try (InputStream is = r.getInputStream()) {
                Map<String, List<String>> raw = objectMapper.readValue(is, new TypeReference<Map<String, List<String>>>() {});
                Map<String, Set<UUID>> out = new HashMap<>();
                for (var e : raw.entrySet()) {
                    Set<UUID> ids = e.getValue().stream().map(UUID::fromString).collect(Collectors.toSet());
                    out.put(e.getKey(), ids);
                }
                return out;
            }
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private PrintWriter writer(File f) throws IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
    }

    private byte[] readClasspathBytes(String classpath) throws IOException {
        ClassPathResource r = new ClassPathResource(classpath);
        try (InputStream is = r.getInputStream()) {
            return is.readAllBytes();
        }
    }

    private String guessName(String classpath) {
        int i = classpath.lastIndexOf('/');
        return i >= 0 ? classpath.substring(i + 1) : classpath;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\p{Punct}+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private ExamDraftRequest toExamDraftRequest(PromptSpec p) {
        ExamDraftRequest r = new ExamDraftRequest();
        r.setTopic(p.topic());
        r.setTotalQuestions(p.numQuestions() == null ? 10 : p.numQuestions());
//        r.setDifficulty(p.difficulty() == null ? "medium" : p.difficulty());
        return r;
    }

    // approximation: chỉ để có avgChars “tạm đủ mô tả”
    private double estimateAvgCharsFromPdf(byte[] pdfBytes, int chunks) {
        if (chunks <= 0) return 0.0;
        // xấp xỉ: dùng size bytes làm proxy; bạn có thể thay bằng PdfExtractor extract text để chính xác hơn
        return (pdfBytes.length * 1.0 / chunks);
    }
}