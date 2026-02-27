package com.khoinguyen.exam.service;

import java.util.Map;
import java.util.UUID;

public record ScoredChunk(
        UUID id,
        String fileId,
        String content,
        Map<String, Object> metadata,
        double vectorScore,
        double bm25Score,
        double fusedScore
) {
}
