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

CREATE TABLE IF NOT EXISTS conversations (
  id                  VARCHAR(128) PRIMARY KEY,
  summary             TEXT,
  summary_until_seq   BIGINT NOT NULL DEFAULT 0,
  next_seq            BIGINT NOT NULL DEFAULT 1,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS conversation_messages (
  id                  UUID PRIMARY KEY,
  conversation_id     VARCHAR(128) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  request_id          UUID NOT NULL,
  seq                 BIGINT NOT NULL,
  role                VARCHAR(32) NOT NULL,
  content             TEXT NOT NULL,
  status              VARCHAR(32) NOT NULL DEFAULT 'COMMITTED',
  tool_name           VARCHAR(128),
  tool_call_id        VARCHAR(128),
  metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
  token_count         INT NOT NULL DEFAULT 0,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (conversation_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_conversation_messages_context
  ON conversation_messages (conversation_id, status, seq);

CREATE INDEX IF NOT EXISTS idx_conversations_updated_at
  ON conversations (updated_at DESC);
