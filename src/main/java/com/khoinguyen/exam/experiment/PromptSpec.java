package com.khoinguyen.exam.experiment;

public record PromptSpec(
        String topic,
        Integer numQuestions,
        String difficulty,
        ConstraintSpec constraints
) {}