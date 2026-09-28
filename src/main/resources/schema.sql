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

CREATE TABLE IF NOT EXISTS knowledge_documents (
  id            UUID PRIMARY KEY,
  file_name     VARCHAR(512) NOT NULL,
  object_key    VARCHAR(1024) NOT NULL,
  size_bytes    BIGINT NOT NULL DEFAULT 0,
  status        VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
  chunk_count   INT NOT NULL DEFAULT 0,
  error_message TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  indexed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_created_at
  ON knowledge_documents (created_at DESC);

ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS document_id UUID;

CREATE INDEX IF NOT EXISTS idx_chunks_document_id
  ON document_chunks (document_id);

-- 历史数据一次性回填：为存量分片补齐文档行（由 document_id IS NULL 自我限流，重复执行影响 0 行）
INSERT INTO knowledge_documents (id, file_name, object_key, size_bytes, status, chunk_count, created_at, updated_at, indexed_at)
SELECT gen_random_uuid(), c.file_name, 'legacy/' || c.file_name, 0, 'INDEXED', COUNT(*),
       MIN(c.created_at), now(), MIN(c.created_at)
FROM document_chunks c
WHERE c.document_id IS NULL
GROUP BY c.file_name;

UPDATE document_chunks c
SET document_id = d.id
FROM knowledge_documents d
WHERE c.document_id IS NULL AND d.object_key = 'legacy/' || c.file_name;
