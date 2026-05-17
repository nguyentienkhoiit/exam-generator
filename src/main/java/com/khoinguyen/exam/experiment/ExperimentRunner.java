package com.khoinguyen.exam.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khoinguyen.exam.dto.ExamDraftRequest;
import com.khoinguyen.exam.dto.ExamDraftResponse;
import com.khoinguyen.exam.service.RagApplicationService;
import com.khoinguyen.exam.service.ScoredChunk;
import com.khoinguyen.exam.service.ingest.IngestService;
import com.khoinguyen.exam.service.ingest.PdfExtractor;
import com.khoinguyen.exam.service.ingest.TokenTextChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private final PdfExtractor pdfExtractor;
    private final TokenTextChunker tokenTextChunker;

    @Value("${experiments.outDir:./exp-out}")
    private String outDir;

    @Value("${experiments.topK:12}")
    private int topK;

    @Value("${experiments.alpha:0.6}")
    private double alpha;

    @Value("${experiments.mmrK:8}")
    private int mmrK;

    @Value("${experiments.mmrLambda:0.7}")
    private double mmrLambda;

    @Value("${experiments.genRuns:6}")
    private int genRuns;

    @Value("${experiments.runExp1:true}")
    private boolean runExp1;

    @Value("${experiments.runExp2:true}")
    private boolean runExp2;

    @Value("${experiments.runExp3:true}")
    private boolean runExp3;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        new File(outDir).mkdirs();

        List<TaskSpec> tasks = loadTasks("bench/tasks.json");
        Map<String, Set<Integer>> annotations = loadAnnotationsOrEmpty("bench/annotations.json");

        log.info("Loaded tasks: {}", tasks.size());
        log.info("Loaded annotations: {}", annotations.size());

        if (runExp1) {
            runExp1(tasks);
        }
        if (runExp2) {
            runExp2(tasks, annotations);
        }
        if (runExp3) {
            runExp3(tasks);
        }

        log.info("DONE. Output in {}", outDir);
    }

    private void runExp1(List<TaskSpec> tasks) throws Exception {
        File csv = new File(outDir, "exp1_chunking.csv");
        try (PrintWriter pw = writer(csv)) {
            pw.println("taskId,pdfClasspath,fileId,numChunks,avgChars,avgWords,shortChunks,longChunks,redundancyRate");

            for (TaskSpec t : tasks) {
                byte[] pdfBytes = readClasspathBytes(t.pdfClasspath());
                ByteArrayMultipartFile mf = new ByteArrayMultipartFile(
                    pdfBytes, "file", guessName(t.pdfClasspath()), "application/pdf"
                );

                ExamDraftRequest req = toExamDraftRequest(t.prompt());
                IngestService.IngestResult ing = ingestService.ingest(req, mf);

                ChunkStats stats = analyzeChunks(pdfBytes);

                pw.printf(
                    Locale.US,
                    "%s,%s,%s,%d,%.2f,%.2f,%d,%d,%.4f%n",
                    t.id(),
                    t.pdfClasspath(),
                    ing.fileId(),
                    stats.numChunks(),
                    stats.avgChars(),
                    stats.avgWords(),
                    stats.shortChunks(),
                    stats.longChunks(),
                    stats.redundancyRate()
                );
            }
        }
        log.info("Exp1 -> {}", csv.getAbsolutePath());
    }

    private void runExp2(List<TaskSpec> tasks, Map<String, Set<Integer>> annotations) throws Exception {
        File csv = new File(outDir, "exp2_retrieval.csv");
        try (PrintWriter pw = writer(csv)) {
            pw.println("taskId,mode,recallAt12,precisionAt12,hitAt12,mrr,redundantCount");

            for (TaskSpec t : tasks) {
                byte[] pdfBytes = readClasspathBytes(t.pdfClasspath());
                ByteArrayMultipartFile mf = new ByteArrayMultipartFile(
                    pdfBytes, "file", guessName(t.pdfClasspath()), "application/pdf"
                );

                ExamDraftRequest req = toExamDraftRequest(t.prompt());
                IngestService.IngestResult ing = ingestService.ingest(req, mf);

                String fileId = ing.fileId();
                String query = req.getTopic();
                boolean hasGtForTask = annotations.containsKey(t.id()) && !annotations.get(t.id()).isEmpty();

                writeRetrievalRow(
                    pw,
                    t,
                    "DENSE_ONLY",
                    hasGtForTask,
                    annotations,
                    retrievalExperimentService.retrieve(
                        fileId, query, topK,
                        RetrievalMode.DENSE_ONLY,
                        alpha,
                        false,
                        mmrK,
                        mmrLambda
                    )
                );

                writeRetrievalRow(
                    pw,
                    t,
                    "BM25_ONLY",
                    hasGtForTask,
                    annotations,
                    retrievalExperimentService.retrieve(
                        fileId, query, topK,
                        RetrievalMode.BM25_ONLY,
                        alpha,
                        false,
                        mmrK,
                        mmrLambda
                    )
                );

                writeRetrievalRow(
                    pw,
                    t,
                    "HYBRID_ALPHA",
                    hasGtForTask,
                    annotations,
                    retrievalExperimentService.retrieve(
                        fileId, query, topK,
                        RetrievalMode.HYBRID_ALPHA_FUSION,
                        alpha,
                        false,
                        mmrK,
                        mmrLambda
                    )
                );

                writeRetrievalRow(
                    pw,
                    t,
                    "HYBRID_ALPHA_MMR",
                    hasGtForTask,
                    annotations,
                    retrievalExperimentService.retrieve(
                        fileId, query, topK,
                        RetrievalMode.HYBRID_ALPHA_FUSION,
                        alpha,
                        true,
                        mmrK,
                        mmrLambda
                    )
                );
            }
        }
        log.info("Exp2 -> {}", csv.getAbsolutePath());
    }

    private void writeRetrievalRow(
        PrintWriter pw,
        TaskSpec t,
        String modeName,
        boolean hasGtForTask,
        Map<String, Set<Integer>> annotations,
        List<ScoredChunk> retrieved
    ) {
        Set<Integer> retIdx = retrieved.stream()
            .map(this::extractChunkIndex)
            .filter(i -> i >= 0)
            .collect(Collectors.toSet());

        int redundant = countRedundantByNormalizedText(retrieved);

        if (!hasGtForTask) {
            pw.printf(
                Locale.US,
                "%s,%s,%s,%s,%s,%s,%d%n",
                t.id(),
                modeName,
                "NA",
                "NA",
                "NA",
                "NA",
                redundant
            );
            return;
        }

        Set<Integer> gt = annotations.get(t.id());

        long hit = gt.stream().filter(retIdx::contains).count();
        double recall = gt.isEmpty() ? 0.0 : (hit * 1.0 / gt.size());
        double precision = retIdx.isEmpty() ? 0.0 : (hit * 1.0 / retIdx.size());
        double hitAtK = hit > 0 ? 1.0 : 0.0;

        double mrr = 0.0;
        for (int rank = 0; rank < retrieved.size(); rank++) {
            int idx = extractChunkIndex(retrieved.get(rank));
            if (gt.contains(idx)) {
                mrr = 1.0 / (rank + 1);
                break;
            }
        }

        pw.printf(
            Locale.US,
            "%s,%s,%.4f,%.4f,%.4f,%.4f,%d%n",
            t.id(),
            modeName,
            recall,
            precision,
            hitAtK,
            mrr,
            redundant
        );
    }

    private int extractChunkIndex(ScoredChunk sc) {
        if (sc == null || sc.metadata() == null) {
            return -1;
        }
        Object v = sc.metadata().get("chunkIndex");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                return -1;
            }
        }
        return -1;
    }

    private int countRedundantByNormalizedText(List<ScoredChunk> chunks) {
        Set<String> seen = new HashSet<>();
        int dup = 0;
        for (ScoredChunk sc : chunks) {
            String norm = normalize(sc.content());
            if (!seen.add(norm)) {
                dup++;
            }
        }
        return dup;
    }

    private void runExp3(List<TaskSpec> tasks) throws Exception {
        File csvRuns = new File(outDir, "exp3_runs.csv");
        File csvAgg = new File(outDir, "exp3_summary.csv");

        List<Long> allLatMs = new ArrayList<>();
        List<Double> allDupRates = new ArrayList<>();

        int totalRuns = 0;
        int validJsonRuns = 0;
        int csrRuns = 0;
        int totalInvalidOptionCount = 0;
        int totalEmptyExplanationCount = 0;

        try (PrintWriter pw = writer(csvRuns)) {
            pw.println("taskId,runIdx,latencyMs,jsonValid,csr,dupRate,qCount,invalidOptionCount,emptyExplanationCount,readingCount,grammarCount,vocabularyCount,listeningCount");

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
                        log.warn("EXP3 generation failed task={} run={} msg={}", t.id(), r, e.getMessage());
                    }

                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    allLatMs.add(ms);

                    int qCount = questionCount(resp);
                    int readingCount = countType(resp, "reading");
                    int grammarCount = countType(resp, "grammar");
                    int vocabularyCount = countType(resp, "vocabulary");
                    int listeningCount = countType(resp, "listening");

                    double dupRate = duplicationRate(resp);
                    allDupRates.add(dupRate);

                    int invalidOptionCount = countInvalidOptions(resp);
                    int emptyExplanationCount = countEmptyExplanation(resp);

                    totalInvalidOptionCount += invalidOptionCount;
                    totalEmptyExplanationCount += emptyExplanationCount;

                    boolean csr = jsonValid && checkConstraints(req, resp);

                    if (jsonValid) {
                        validJsonRuns++;
                    }
                    if (csr) {
                        csrRuns++;
                    }

                    pw.printf(
                        Locale.US,
                        "%s,%d,%d,%d,%d,%.4f,%d,%d,%d,%d,%d,%d,%d%n",
                        t.id(),
                        r,
                        ms,
                        jsonValid ? 1 : 0,
                        csr ? 1 : 0,
                        dupRate,
                        qCount,
                        invalidOptionCount,
                        emptyExplanationCount,
                        readingCount,
                        grammarCount,
                        vocabularyCount,
                        listeningCount
                    );
                }
            }
        }

        double jsonValidRate = totalRuns == 0 ? 0.0 : (validJsonRuns * 1.0 / totalRuns);
        double csrRate = totalRuns == 0 ? 0.0 : (csrRuns * 1.0 / totalRuns);
        double avgDupRate = average(allDupRates);

        long p50 = percentile(allLatMs, 50);
        long p95 = percentile(allLatMs, 95);

        try (PrintWriter pw = writer(csvAgg)) {
            pw.println("totalRuns,jsonValidRate,csrRate,avgDupRate,latencyP50Ms,latencyP95Ms,totalInvalidOptionCount,totalEmptyExplanationCount");
            pw.printf(
                Locale.US,
                "%d,%.4f,%.4f,%.4f,%d,%d,%d,%d%n",
                totalRuns,
                jsonValidRate,
                csrRate,
                avgDupRate,
                p50,
                p95,
                totalInvalidOptionCount,
                totalEmptyExplanationCount
            );
        }

        log.info("Exp3 -> {}, {}", csvRuns.getAbsolutePath(), csvAgg.getAbsolutePath());
    }

    private boolean checkConstraints(ExamDraftRequest req, ExamDraftResponse resp) {
        if (resp == null || resp.getQuestions() == null) {
            return false;
        }

        if (resp.getQuestions().size() != req.getTotalQuestions()) {
            return false;
        }

        if (req.getTypeDistribution() != null && !req.getTypeDistribution().isEmpty()) {
            Map<String, Long> actualTypeDist = resp.getQuestions().stream()
                .map(q -> q.getQuestion_type() == null ? "" : q.getQuestion_type().toLowerCase(Locale.ROOT))
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

            for (Map.Entry<String, Integer> e : req.getTypeDistribution().entrySet()) {
                long got = actualTypeDist.getOrDefault(e.getKey().toLowerCase(Locale.ROOT), 0L);
                if (got != e.getValue()) {
                    return false;
                }
            }
        }

        if (req.getLevelDistribution() != null && !req.getLevelDistribution().isEmpty()) {
            Map<String, Long> actualLevelDist = resp.getQuestions().stream()
                .map(q -> q.getDifficulty() == null ? "" : q.getDifficulty().toLowerCase(Locale.ROOT))
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

            for (Map.Entry<String, Integer> e : req.getLevelDistribution().entrySet()) {
                long got = actualLevelDist.getOrDefault(e.getKey().toLowerCase(Locale.ROOT), 0L);
                if (got != e.getValue()) {
                    return false;
                }
            }
        }

        return countInvalidOptions(resp) == 0 && countEmptyExplanation(resp) == 0;
    }

    private int questionCount(ExamDraftResponse resp) {
        return (resp == null || resp.getQuestions() == null) ? 0 : resp.getQuestions().size();
    }

    private int countType(ExamDraftResponse resp, String key) {
        if (resp == null || resp.getQuestions() == null) {
            return 0;
        }
        int c = 0;
        for (ExamDraftResponse.QuestionDraftResponse q : resp.getQuestions()) {
            String t = q.getQuestion_type();
            if (t != null && t.equalsIgnoreCase(key)) {
                c++;
            }
        }
        return c;
    }

    private double duplicationRate(ExamDraftResponse resp) {
        if (resp == null || resp.getQuestions() == null || resp.getQuestions().isEmpty()) {
            return 0.0;
        }

        List<String> qs = resp.getQuestions().stream()
            .map(ExamDraftResponse.QuestionDraftResponse::getQuestion_text)
            .filter(Objects::nonNull)
            .map(this::normalize)
            .filter(s -> !s.isBlank())
            .toList();

        if (qs.isEmpty()) {
            return 0.0;
        }

        int total = qs.size();
        int unique = new HashSet<>(qs).size();
        return (total - unique) * 1.0 / total;
    }

    private int countInvalidOptions(ExamDraftResponse resp) {
        if (resp == null || resp.getQuestions() == null) {
            return 0;
        }

        int c = 0;
        for (ExamDraftResponse.QuestionDraftResponse q : resp.getQuestions()) {
            if (isBlank(q.getOption_a())
                || isBlank(q.getOption_b())
                || isBlank(q.getOption_c())
                || isBlank(q.getOption_d())
                || isBlank(q.getCorrect_answer())) {
                c++;
            }
        }
        return c;
    }

    private int countEmptyExplanation(ExamDraftResponse resp) {
        if (resp == null || resp.getQuestions() == null) {
            return 0;
        }

        int c = 0;
        for (ExamDraftResponse.QuestionDraftResponse q : resp.getQuestions()) {
            if (isBlank(q.getExplanation())) {
                c++;
            }
        }
        return c;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private long percentile(List<Long> values, int pct) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil((pct / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        DoubleSummaryStatistics stats = values.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        return stats.getAverage();
    }

    private List<TaskSpec> loadTasks(String classpath) throws IOException {
        ClassPathResource r = new ClassPathResource(classpath);
        try (InputStream is = r.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<TaskSpec>>() {
            });
        }
    }

    private Map<String, Set<Integer>> loadAnnotationsOrEmpty(String classpath) {
        try {
            ClassPathResource r = new ClassPathResource(classpath);
            if (!r.exists()) {
                return Map.of();
            }

            try (InputStream is = r.getInputStream()) {
                String rawText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String cleanText = rawText
                    .replaceAll("(?m)^\\s*//.*$", "")
                    .trim();

                if (cleanText.isBlank()) {
                    return Map.of();
                }

                Map<String, List<Integer>> raw = objectMapper.readValue(
                    cleanText,
                    new TypeReference<Map<String, List<Integer>>>() {
                    }
                );

                Map<String, Set<Integer>> out = new HashMap<>();
                for (Map.Entry<String, List<Integer>> e : raw.entrySet()) {
                    out.put(e.getKey(), new HashSet<>(e.getValue()));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("Cannot load annotations from {}. EXP2 will run without GT. msg={}", classpath, e.getMessage());
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
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
            .replaceAll("\\p{Punct}+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private ChunkStats analyzeChunks(byte[] pdfBytes) throws IOException {
        String extracted;
        try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
            extracted = pdfExtractor.extractText(is);
        }

        List<String> chunks = tokenTextChunker.chunk(extracted);
        if (chunks.isEmpty()) {
            return new ChunkStats(0, 0.0, 0.0, 0, 0, 0.0);
        }

        int totalChars = chunks.stream().mapToInt(String::length).sum();
        int totalWords = chunks.stream().mapToInt(this::wordCount).sum();

        double avgChars = totalChars * 1.0 / chunks.size();
        double avgWords = totalWords * 1.0 / chunks.size();

        long shortChunks = chunks.stream().filter(c -> c.length() < 300).count();
        long longChunks = chunks.stream().filter(c -> c.length() > 2500).count();

        int uniqueChunks = new HashSet<>(chunks.stream().map(this::normalize).toList()).size();
        double redundancyRate = chunks.isEmpty()
            ? 0.0
            : ((chunks.size() - uniqueChunks) * 1.0 / chunks.size());

        return new ChunkStats(
            chunks.size(),
            avgChars,
            avgWords,
            (int) shortChunks,
            (int) longChunks,
            redundancyRate
        );
    }

    private int wordCount(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        return s.trim().split("\\s+").length;
    }

    private ExamDraftRequest toExamDraftRequest(PromptSpec p) {
        ExamDraftRequest r = new ExamDraftRequest();
        r.setTopic(p.topic());
        r.setTotalQuestions(p.numQuestions() == null ? 10 : p.numQuestions());

        Map<String, Integer> typeDistribution = new LinkedHashMap<>();
        if (p.constraints() != null) {
            if (p.constraints().mcq() != null) {
                typeDistribution.put("grammar", p.constraints().mcq());
            }
            if (p.constraints().trueFalse() != null) {
                typeDistribution.put("reading", p.constraints().trueFalse());
            }
            if (p.constraints().shortAnswer() != null) {
                typeDistribution.put("vocabulary", p.constraints().shortAnswer());
            }
        }
        if (!typeDistribution.isEmpty()) {
            r.setTypeDistribution(typeDistribution);
        }

        Map<String, Integer> levelDistribution = new LinkedHashMap<>();
        String difficulty = p.difficulty() == null ? "medium" : p.difficulty().trim().toLowerCase(Locale.ROOT);
        if (difficulty.equals("easy")) {
            levelDistribution.put("easy", r.getTotalQuestions());
        } else if (difficulty.equals("hard")) {
            levelDistribution.put("hard", r.getTotalQuestions());
        } else {
            levelDistribution.put("medium", r.getTotalQuestions());
        }
        r.setLevelDistribution(levelDistribution);

        return r;
    }

    private record ChunkStats(
        int numChunks,
        double avgChars,
        double avgWords,
        int shortChunks,
        int longChunks,
        double redundancyRate
    ) {
    }
}