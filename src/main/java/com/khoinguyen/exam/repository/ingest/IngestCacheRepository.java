package com.khoinguyen.exam.repository.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Persists mapping from file content hash -> fileId so that /ask can avoid re-ingesting the same PDF.
 * This keeps a single /ask endpoint while drastically reducing latency for repeat calls.
 */
@Repository
public class IngestCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    public IngestCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CachedIngest> findBySha256(String sha256) {
        var rows = jdbcTemplate.query(
                "SELECT file_id, chunks FROM ingest_files WHERE sha256 = ?",
                (rs, rowNum) -> new CachedIngest(rs.getString("file_id"), rs.getInt("chunks")),
                sha256
        );
        return rows.stream().findFirst();
    }

    public void upsert(String sha256, String fileId, int chunks, String sourceName, long sizeBytes) {
        jdbcTemplate.update(
                "INSERT INTO ingest_files(sha256, file_id, chunks, source_name, size_bytes, created_at) " +
                        "VALUES(?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (sha256) DO UPDATE SET " +
                        "file_id = EXCLUDED.file_id, " +
                        "chunks = EXCLUDED.chunks, " +
                        "source_name = EXCLUDED.source_name, " +
                        "size_bytes = EXCLUDED.size_bytes, " +
                        "created_at = EXCLUDED.created_at",
                sha256, fileId, chunks, sourceName, sizeBytes, Timestamp.from(Instant.now())
        );
    }

    public record CachedIngest(String fileId, int chunks) {
    }
}
