package com.khoinguyen.exam.service.llm;

import com.khoinguyen.exam.dto.ExamDraftRequest;
import com.khoinguyen.exam.dto.ExamDraftResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ExamDraftPromptService {

    private static final Logger log = LoggerFactory.getLogger(ExamDraftPromptService.class);

    // =============================
    // System prompt (kept in code)
    // =============================
    private static final String SYSTEM_TEMPLATE = """
            Bạn là chuyên gia thiết kế đề thi tiếng Anh (TOEIC/IELTS).
            Bạn PHẢI chỉ xuất ra MỘT JSON object hợp lệ theo schema được cung cấp.
            Không markdown. Không thêm chữ ngoài JSON. Không giải thích ngoài schema.
            """;

    // =============================
    // External templates in resources
    // =============================
    private static final String PROMPT_TEMPLATE_FILE = "promptTemplates/systemPromptTemplate.st";
    private static final String OUTPUT_FORMAT_TEMPLATE_FILE = "promptTemplates/outputFormatTemplate.st";

    private final AgentGenerativeService agentGenerativeService;

    public ExamDraftPromptService(AgentGenerativeService agentGenerativeService) {
        this.agentGenerativeService = agentGenerativeService;
    }

    // ===== helpers =====
    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** Simple {var} replacement (consistent with your current template style). */
    private static String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static String toHumanDistribution(Map<String, Integer> dist) {
        if (dist == null || dist.isEmpty()) return "";
        return dist.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String loadFromResource(String path) {
        try {
            var res = new ClassPathResource(path);
            try (var in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load template: " + path, e);
        }
    }

    private static String buildOutputFormatFromTemplate(int totalQuestions) {
        String tpl = loadFromResource(OUTPUT_FORMAT_TEMPLATE_FILE);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("total_questions", String.valueOf(totalQuestions));
        return render(tpl, v);
    }

    private static void validateTotalOnly(ExamDraftRequest req) {
        if (req == null) throw new IllegalArgumentException("ExamDraftRequest is null");
        if (req.getTotalQuestions() <= 0) throw new IllegalArgumentException("totalQuestions must be > 0");
    }

    private PromptBundle buildPrompt(ExamDraftRequest req, String context) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("topic", safe(req.getTopic()));
        vars.put("total_questions", String.valueOf(req.getTotalQuestions()));
        vars.put("question_type_distribution_human", toHumanDistribution(req.getTypeDistribution()));
        vars.put("difficulty_distribution_human", toHumanDistribution(req.getLevelDistribution()));
        vars.put("explanation_language_human", "vi");
        vars.put("context", safe(context));

        // Render output schema from outputFormatTemplate.st
        vars.put("output_format", buildOutputFormatFromTemplate(req.getTotalQuestions()));

        // System (still uses {var} replacement if you ever add placeholders later)
        String system = render(SYSTEM_TEMPLATE, vars);

        // Prompt body from systemPromptTemplate.st
        String promptTpl = loadFromResource(PROMPT_TEMPLATE_FILE);
        String user = render(promptTpl, vars);

        return new PromptBundle(system, user);
    }

    /**
     * Build prompt (system + user) and delegate to AgentGenerativeService (Spring AI ChatClient).
     */
    public ExamDraftResponse generateExamDraft(ExamDraftRequest request, String context) {
        validateTotalOnly(request);
        PromptBundle pb = buildPrompt(request, context);

        // AgentGenerativeService.generate(String prompt) uses only .user(prompt),
        // so we embed system+user into a single prompt string.
        String fullPrompt = "SYSTEM:\n" + pb.system() + "\n\nUSER:\n" + pb.user();

        long t0 = System.nanoTime();
        ExamDraftResponse out = agentGenerativeService.generate(fullPrompt);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int qCount = (out == null || out.getQuestions() == null) ? 0 : out.getQuestions().size();
        log.info("event=llm_generate_exam_draft_ok questions={} ms={}", qCount, ms);
        return out;
    }

    private record PromptBundle(String system, String user) {
    }
}