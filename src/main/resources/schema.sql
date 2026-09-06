CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document_chunks (
  id            UUID PRIMARY KEY,
  file_name     VARCHAR(512) NOT NULL,
  title         VARCHAR(512),
  content       TEXT NOT NULL,
  tokens        TEXT[] NOT NULL DEFAULT '{}',
  embedding     vector(1024) NOT NULL,
  chunk_index   INT NOT NULL,
  metadata      JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_file_name ON document_chunks (file_name);
