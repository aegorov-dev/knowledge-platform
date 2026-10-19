# Knowledge Platform — UML & Data Schema

Rendered diagrams (Mermaid) for the whole platform: system context, component
internals, database schema (ER), domain/provider class models, and the two core
runtime sequences. All diagrams render natively on GitHub and in most IDEs.

- [1. System context / deployment topology](#1-system-context--deployment-topology)
- [2. Component diagram (per service)](#2-component-diagram-per-service)
- [3. Database schema (ER)](#3-database-schema-er)
- [4. Class diagram — provider seams & domain](#4-class-diagram--provider-seams--domain)
- [5. Sequence — document upload → indexing](#5-sequence--document-upload--indexing)
- [6. Sequence — RAG chat](#6-sequence--rag-chat)

The platform is three independent Spring Boot services plus an nginx UI. Each
service **owns its own database schema** (no shared domain module); they
communicate only via a Kafka event and a read-only SQL view of chunks. Providers
(embedding / LLM) and object storage are pluggable behind interfaces.

---

## 1. System context / deployment topology

```mermaid
flowchart TB
    user([Browser user])

    subgraph edge[Edge]
      ui["ui (nginx)<br/>static SPA + same-origin reverse proxy<br/>:80"]
    end

    subgraph apps[Application services]
      doc["document-service<br/>Servlet · :8080<br/>upload + outbox"]
      idx["indexing-service<br/>Kafka consumer · actuator :8080<br/>chunk + embed"]
      rag["rag-service<br/>WebFlux · :8082<br/>retrieve + stream chat"]
    end

    subgraph infra[Backing infrastructure]
      pg[("PostgreSQL + pgvector<br/>schemas: document_service,<br/>indexing_service, rag_service")]
      kafka{{"Kafka<br/>topic: knowledge.documents.v1"}}
      obj[("Object storage<br/>local FS or AWS S3")]
    end

    subgraph ext[External model providers]
      openai["OpenAI / Azure OpenAI<br/>(optional; stub is default)"]
    end

    user -->|HTTPS| ui
    ui -->|/api/v1/documents| doc
    ui -->|/api/v1/rag| rag

    doc -->|write file| obj
    doc -->|documents + outbox_events| pg
    doc -.->|publish DocumentUploadedV1| kafka

    kafka -.->|consume| idx
    idx -->|read file| obj
    idx -->|indexed_documents + document_chunks| pg
    idx -->|embed chunks| openai

    rag -->|conversations + messages| pg
    rag -->|read-only similarity search<br/>document_chunks| pg
    rag -->|embed query + chat| openai
```

**Key boundary facts**

| Aspect | Detail |
|---|---|
| Sync coupling | None between services. Only Kafka (`document → indexing`) + a read-only chunk query (`rag → indexing_service.document_chunks`). |
| Storage sharing | `document-service` **writes**, `indexing-service` **reads** the same object store (shared FS locally; **S3** in prod). |
| Embedding parity | `indexing-service` (stored chunk vectors) and `rag-service` (query vector) MUST use the same provider/model/dimensions (`vector(768)`, cosine). |
| Providers | Selected by `knowledge-platform.{embedding,llm}.provider`; `stub` is the offline default. |

---

## 2. Component diagram (per service)

```mermaid
flowchart LR
    subgraph DOC[document-service]
      dctl[DocumentController]
      dupl[DocumentUploadService]
      dpers[DocumentPersistenceService]
      fstore[[FileStorage port]]
      lfs[LocalFileStorage / S3FileStorage]
      drepo[(DocumentRepository)]
      orepo[(OutboxEventRepository)]
      opub[OutboxPublisherService<br/>polling relay]
      dctl --> dupl --> fstore
      fstore -.-> lfs
      dupl --> dpers --> drepo
      dpers --> orepo
      opub --> orepo
    end

    subgraph IDX[indexing-service]
      dul[DocumentUploadedListener<br/>Kafka @KafkaListener]
      dis[DocumentIndexingService]
      sr[[StorageReader port]]
      chunk[TextChunker]
      iemb[[EmbeddingModel port]]
      iembimpl[Stub / OpenAI / Ollama]
      dcw[DocumentChunkWriter<br/>JdbcClient]
      idrepo[(IndexedDocumentRepository)]
      dul --> dis
      dis --> sr
      dis --> chunk
      dis --> iemb
      iemb -.-> iembimpl
      dis --> dcw
      dis --> idrepo
    end

    subgraph RAG[rag-service]
      rctl[RagChatController]
      rcon[RagConversationController]
      rcs[RagChatService]
      cmp[ChatMemoryProvider]
      remb[[EmbeddingModel port]]
      rembimpl[Stub / SpringAiOpenAI / Azure]
      cret[[ChunkRetriever port]]
      pgcr[PgVectorChunkRetriever]
      llm[[ChatLlm port]]
      llmimpl[Stub / OpenAI / Azure]
      rctl --> rcs
      rcon --> cmp
      rcs --> remb
      remb -.-> rembimpl
      rcs --> cret
      cret -.-> pgcr
      rcs --> llm
      llm -.-> llmimpl
      rcs --> cmp
    end

    opub -. DocumentUploadedV1 .-> dul
    pgcr -. reads .-> dcw
```

---

## 3. Database schema (ER)

One physical PostgreSQL database, three isolated schemas. `rag-service` reads
`indexing_service.document_chunks` for similarity search but never writes it.

```mermaid
erDiagram
    documents ||--o{ outbox_events : "emits"
    indexed_documents ||--o{ document_chunks : "has"
    rag_conversations ||--o{ rag_messages : "contains"

    documents {
      UUID id PK
      varchar file_name UK "unique"
      varchar content_type
      bigint size
      varchar status "UPLOADED..."
      varchar storage_key "object key"
      timestamptz created_at
      timestamptz updated_at
      bigint version "optimistic lock"
    }

    outbox_events {
      UUID id PK
      UUID aggregate_id FK "-> documents.id"
      varchar aggregate_type
      varchar event_type
      text payload "JSON"
      timestamptz created_at
      timestamptz published_at "null until sent"
      varchar claimed_by
      timestamptz claimed_until
      int attempts
    }

    indexed_documents {
      UUID document_id PK "= documents.id"
      varchar file_name
      varchar content_type
      varchar storage_key
      varchar status "INDEXED..."
      int chunk_count
      timestamptz indexed_at
      timestamptz updated_at
      varchar last_event_id UK "idempotency"
    }

    document_chunks {
      UUID id PK
      UUID document_id FK "-> indexed_documents"
      int chunk_index
      text content
      vector_768 embedding "pgvector; cosine"
      timestamptz created_at
    }

    rag_conversations {
      UUID id PK
      varchar user_id
      timestamptz created_at
      timestamptz last_access_at
    }

    rag_messages {
      UUID id PK
      UUID conversation_id FK "-> rag_conversations"
      int seq "unique per conversation"
      varchar role "USER|ASSISTANT|SYSTEM (check)"
      text content
      timestamptz created_at
    }
```

**Cross-schema link (no DB-level FK — a deliberate service boundary):**
`indexed_documents.document_id` and `document_chunks.document_id` equal
`documents.id`; the value travels in the `DocumentUploadedV1` Kafka event.

**Notable constraints & indexes**

- `documents.file_name` UNIQUE (upsert-by-name); `idx_documents_status_created_at`.
- `outbox_events`: `idx_outbox_events_unpublished(published_at, claimed_until, created_at)` powers the polling relay's claim query.
- `indexed_documents.last_event_id` UNIQUE → **idempotent** consumption (duplicate Kafka delivery is a no-op).
- `document_chunks(document_id, chunk_index)` index; FK `ON DELETE CASCADE`.
- `rag_messages`: UNIQUE `(conversation_id, seq)` + CHECK on `role`.

---

## 4. Class diagram — provider seams & domain

The pluggable seams (Strategy pattern via `@ConditionalOnProperty`) are the heart
of the design.

```mermaid
classDiagram
    direction LR

    class EmbeddingModel {
      <<interface>>
      +float[] embed(String text)
      +int dimensions()
    }
    class StubEmbeddingModel
    class SpringAiEmbeddingModelAdapter
    class AzureOpenAiEmbeddingModel
    EmbeddingModel <|.. StubEmbeddingModel
    EmbeddingModel <|.. SpringAiEmbeddingModelAdapter
    EmbeddingModel <|.. AzureOpenAiEmbeddingModel

    class ChatLlm {
      <<interface>>
      +Flux~String~ stream(LlmContext ctx)
    }
    class StubChatLlm
    class SpringAiChatLlm
    class AzureOpenAiChatLlm
    ChatLlm <|.. StubChatLlm
    ChatLlm <|.. SpringAiChatLlm
    ChatLlm <|.. AzureOpenAiChatLlm

    class ChunkRetriever {
      <<interface>>
      +List~RetrievedChunk~ search(float[] q, int topK)
    }
    class PgVectorChunkRetriever
    ChunkRetriever <|.. PgVectorChunkRetriever

    class RagChatService {
      +Flux~RagChatChunk~ chat(user, convId, question)
      +Mono~List~SourceRef~~ search(question, topK)
    }
    class ChatMemoryProvider
    class RagProperties {
      +String systemPrompt
      +String documentLinkBase
      +int topK
      +int maxContextChars
    }

    RagChatService --> EmbeddingModel
    RagChatService --> ChunkRetriever
    RagChatService --> ChatLlm
    RagChatService --> ChatMemoryProvider
    RagChatService --> RagProperties
    ChatLlm ..> LlmContext
    ChunkRetriever ..> RetrievedChunk
    RagChatService ..> SourceRef

    class LlmContext {
      +String systemPrompt
      +List~Turn~ history
      +List~RetrievedChunk~ chunks
      +String question
    }
    class RetrievedChunk {
      +UUID documentId
      +int chunkIndex
      +String content
      +double score
      +String fileName
      +String link
    }
    class SourceRef {
      +UUID documentId
      +String fileName
      +String link
      +int chunkIndex
      +double score
      +String snippet
    }
```

```mermaid
classDiagram
    direction LR
    note for FileStorage "document-service (write side)"
    class FileStorage {
      <<interface>>
      +store(key, stream, len, contentType)
      +delete(key)
    }
    class LocalFileStorage
    class S3FileStorage
    FileStorage <|.. LocalFileStorage
    FileStorage <|.. S3FileStorage

    class DocumentUploadService
    class DocumentPersistenceService
    class OutboxPublisherService
    DocumentUploadService --> FileStorage
    DocumentUploadService --> DocumentPersistenceService
    DocumentPersistenceService --> DocumentEntity
    DocumentPersistenceService --> OutboxEventEntity
    OutboxPublisherService --> OutboxEventEntity

    note for StorageReader "indexing-service (read side)"
    class StorageReader {
      <<interface>>
      +byte[] read(String key)
    }
    class DocumentIndexingService
    class TextChunker
    class DocumentChunkWriter
    DocumentIndexingService --> StorageReader
    DocumentIndexingService --> TextChunker
    DocumentIndexingService --> EmbeddingModel
    DocumentIndexingService --> DocumentChunkWriter
    DocumentIndexingService --> IndexedDocumentEntity
```

---

## 5. Sequence — document upload → indexing

Transactional **outbox** guarantees the event is published iff the document row
is committed; the consumer is **idempotent** on `last_event_id`.

```mermaid
sequenceDiagram
    autonumber
    actor Admin
    participant UI as ui (nginx)
    participant Doc as document-service
    participant Store as Object store (FS/S3)
    participant DB as PostgreSQL
    participant K as Kafka
    participant Idx as indexing-service
    participant Emb as EmbeddingModel

    Admin->>UI: POST /api/v1/documents (multipart, admin)
    UI->>Doc: proxy upload
    Doc->>Store: store(storageKey, bytes)
    Doc->>DB: INSERT documents + outbox_events (one tx)
    Doc-->>UI: 202 Accepted (documentId)

    loop outbox relay (poll)
      Doc->>DB: claim unpublished outbox row
      Doc->>K: publish DocumentUploadedV1
      Doc->>DB: mark published_at
    end

    K-->>Idx: DocumentUploadedV1
    Idx->>DB: upsert indexed_documents (skip if last_event_id seen)
    Idx->>Store: read(storageKey)
    Idx->>Idx: chunk text
    Idx->>Emb: embed(chunk) x N
    Idx->>DB: saveAndFlush parent, then JDBC insert chunks (+embedding)
    Idx->>DB: status=INDEXED, chunk_count=N
```

---

## 6. Sequence — RAG chat

Reactive streaming (WebFlux). Blocking JDBC/embedding/LLM calls run on the
bounded-elastic scheduler; the answer streams as `META → SOURCES → DELTA* → DONE`.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as ui (nginx)
    participant Rag as rag-service
    participant Mem as ChatMemoryProvider
    participant Emb as EmbeddingModel
    participant Ret as PgVectorChunkRetriever
    participant DB as PostgreSQL (pgvector)
    participant LLM as ChatLlm

    User->>UI: POST /api/v1/rag/chat {question} (user)
    UI->>Rag: proxy (stream)
    Rag->>Mem: load history + append USER msg
    Rag->>Emb: embed(question)
    Rag->>Ret: search(queryVec, topK)
    Ret->>DB: ORDER BY embedding <=> :q LIMIT k (JOIN indexed_documents for name)
    DB-->>Ret: top-k chunks (+ fileName)
    Rag-->>UI: META {conversationId}
    Rag-->>UI: SOURCES [{documentId, fileName, link, score, snippet}]
    Rag->>LLM: stream(LlmContext: systemPrompt + chunks + history + question)
    loop tokens
      LLM-->>Rag: fragment
      Rag-->>UI: DELTA {content}
    end
    Rag->>Mem: append ASSISTANT msg
    Rag-->>UI: DONE {timings}
```

> The hardened default `systemPrompt` (see `RagProperties.DEFAULT_SYSTEM_PROMPT`)
> constrains the model to the provided context, forbids guessing, resists prompt
> injection from document/user content, and requires every answer to cite the
> source **document name + link**.
