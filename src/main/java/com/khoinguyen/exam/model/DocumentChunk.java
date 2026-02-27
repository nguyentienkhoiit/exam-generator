package com.khoinguyen.exam.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DocumentChunk(
        UUID id,
        String fileId,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
