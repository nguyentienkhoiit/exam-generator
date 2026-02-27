package com.khoinguyen.exam.dto;

import lombok.Data;

import java.util.List;

@Data
public class ExamDraftResponse {
    private List<QuestionDraftResponse> questions;

    @Data
    public static class QuestionDraftResponse {
        private String question_text;
        private String question_type;
        private String difficulty;

        private String option_a;
        private String option_b;
        private String option_c;
        private String option_d;

        private String correct_answer;

        private String explanation;
        private String explanation_wrong_a;
        private String explanation_wrong_b;
        private String explanation_wrong_c;
        private String explanation_wrong_d;

        private String source_excerpt;
        private Integer source_page_number;
    }
}
