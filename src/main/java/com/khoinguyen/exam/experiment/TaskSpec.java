package com.khoinguyen.exam.experiment;

public record TaskSpec(
        String id,
        String pdfClasspath,
        PromptSpec prompt
) {}