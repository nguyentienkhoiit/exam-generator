package com.khoinguyen.exam.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class PromptRequest {
    private String topic;
    private Integer totalQuestions;
    private String explanationLanguage;
    private String difficulty;
    private String language;
}
