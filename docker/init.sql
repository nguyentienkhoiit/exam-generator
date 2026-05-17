-- -- ============================================
-- -- Extensions
-- -- ============================================
-- CREATE
-- EXTENSION IF NOT EXISTS vector;
-- CREATE
-- EXTENSION IF NOT EXISTS "uuid-ossp";
-- CREATE
-- EXTENSION IF NOT EXISTS hstore;
--
-- -- ============================================
-- -- Vector store table (Spring AI should point to this table name)
-- -- Default name: vector_store
-- -- IMPORTANT: embedding dimension MUST match your embedding model output dim.
-- -- ============================================
-- CREATE TABLE IF NOT EXISTS vector_store
-- (
--     id
--     uuid
--     PRIMARY
--     KEY
--     DEFAULT
--     uuid_generate_v4
-- (
-- ),
--     content text NOT NULL,
--     metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
--     embedding vector
-- (
--     768
-- ) NOT NULL,
--     file_id text GENERATED ALWAYS AS
-- (
-- (
--     metadata
--     -
--     >>
--     'fileId'
-- )) STORED,
--     created_at timestamp NOT NULL DEFAULT now
-- (
-- )
--     );
--
-- -- Filter index
-- CREATE INDEX IF NOT EXISTS idx_vector_store_file_id ON vector_store(file_id);
--
-- -- Optional (useful if you filter by other metadata keys)
-- CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_gin ON vector_store USING gin (metadata);
--
-- -- ANN vector index for low latency
-- CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
--     ON vector_store USING hnsw (embedding vector_cosine_ops);
--
-- -- Maintenance
-- CREATE INDEX IF NOT EXISTS idx_vector_store_created_at ON vector_store(created_at);
--
-- -- ============================================
-- -- Ingest cache: avoid re-ingesting same PDF on repeated /ask calls
-- -- ============================================
-- CREATE TABLE IF NOT EXISTS ingest_files
-- (
--     sha256
--     text
--     PRIMARY
--     KEY,
--     file_id
--     text
--     NOT
--     NULL,
--     chunks
--     int
--     NOT
--     NULL,
--     source_name
--     text,
--     size_bytes
--     bigint,
--     created_at
--     timestamp
--     NOT
--     NULL
--     DEFAULT
--     now
-- (
-- )
--     );
--
-- CREATE INDEX IF NOT EXISTS idx_ingest_files_created_at
--     ON ingest_files(created_at);


-- ============================================
-- Extensions
-- ============================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS hstore;

-- ============================================
-- Vector store table
-- ============================================
CREATE TABLE IF NOT EXISTS vector_store
(
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

    content text NOT NULL,

    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,

    embedding vector(768) NOT NULL,

    -- FIX: correct JSONB operator is ->>
    file_id text GENERATED ALWAYS AS (
                                         metadata ->> 'fileId'
                                     ) STORED,

    created_at timestamp NOT NULL DEFAULT now()
    );

-- ============================================
-- Indexes
-- ============================================

-- Filter by file_id
CREATE INDEX IF NOT EXISTS idx_vector_store_file_id
    ON vector_store(file_id);

-- JSONB GIN index
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_gin
    ON vector_store USING gin (metadata);

-- Vector ANN index (HNSW - recommended for pgvector)
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- Time-based queries
CREATE INDEX IF NOT EXISTS idx_vector_store_created_at
    ON vector_store(created_at);

-- ============================================
-- Ingest cache table
-- ============================================
CREATE TABLE IF NOT EXISTS ingest_files
(
    sha256 text PRIMARY KEY,

    file_id text NOT NULL,

    chunks int NOT NULL,

    source_name text,

    size_bytes bigint,

    created_at timestamp NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS idx_ingest_files_created_at
    ON ingest_files(created_at);