package com.khoinguyen.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExamDraftRequest {

    private String fileId;
    private String fileName;

    private String pdfText;

    private String topic;
    private int totalQuestions;

    private Map<String, Integer> levelDistribution;
    private Map<String, Integer> typeDistribution;
    private Long duration;
}
