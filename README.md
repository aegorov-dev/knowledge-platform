# Knowledge Platform

Knowledge Platform is organized as a monorepo with independently runnable services.
A reviewer can start everything with a single `docker compose` command, open a
browser, upload a document, and ask a natural-language question to get a streamed,
source-attributed answer — fully offline, with stub LLM and stub embeddings (no
API keys). Real providers (local Ollama or OpenAI) are switched on by
configuration only — see [Providers](#providers-stub-ollama-openai-azure).

Implemented services:

- `document-service/` — accepts documents, stores them on local disk, persists
  metadata in PostgreSQL, and writes an outbox event in the same transaction so
  Kafka publication stays asynchronous.
- `indexing-service/` — consumes `DocumentUploadedV1` from Kafka, reads files from
  shared storage, extracts text (PDFBox), chunks content, generates embeddings, and
  persists chunk records with vectors in PostgreSQL (pgvector).
- `rag-service/` — the RAG pipeline: embeds a question, runs a pgvector similarity
  search over indexed chunks, assembles an LLM context, and streams a
  source-attributed answer as NDJSON. Reactive (Spring WebFlux).
- `ui/` — a single static demo page served by nginx, which also reverse-proxies the
  APIs so the browser talks to one origin (no CORS).

## Architecture

- Spring Boot 4.1, Java 25, Lombok
- PostgreSQL (pgvector) + Liquibase (each service owns its schema)
- Apache Kafka (transactional outbox → at-least-once)
- Spring AI seams for embeddings + LLM (stub by default; Ollama, OpenAI; Azure OpenAI placeholder)
- Local file storage shared between document- and indexing-service
- Actuator health endpoints; demo-grade Spring Security (role-based)

## End-to-end flow

```mermaid
flowchart LR
    subgraph Browser
        UI[Static UI]
    end
    UI -->|upload| DS[document-service]
    UI -->|POST /rag/chat NDJSON| RS[rag-service]
    DS -->|file| VOL[(shared uploads volume)]
    DS -->|outbox row| PG[(PostgreSQL + pgvector)]
    DS -->|DocumentUploadedV1| K[(Kafka)]
    K --> IS[indexing-service]
    VOL --> IS
    IS -->|chunks + embeddings| PG
    RS -->|embed question| STUB[[EmbeddingModel stub/ollama/openai]]
    RS -->|cosine top-K read| PG
    RS -->|stream answer| LLM[[ChatLlm stub/ollama/openai]]
```

## Run the demo

Boot the whole platform (builds images on first run):

```bash
docker compose -f compose.full.yaml up --build
```

Then:

1. Open the UI at <http://localhost:8088>.
2. Select the **admin** demo user (top-right) and **upload** a `.txt`, `.md`, or
   `.pdf` document.
3. Wait a few seconds for indexing (Kafka → embeddings → pgvector).
4. Type a question and click **Ask**. The answer streams token-by-token; the right
   panel shows the retrieved **sources** (document id, chunk index, score, snippet)
   and the **timings** (embedding / retrieval / llm / total).

All providers default to offline stubs, so no secrets are required. To use a real
model see [Providers](#providers-stub-ollama-openai-azure).

Ports: UI `8088`, document-service `8080`, rag-service `8082`, PostgreSQL `5432`,
Kafka `19092` (host).

### Demo users and roles

Credentials are intentionally visible — they are demo-only, not production auth.

| Username | Password | Role         | Can do                                            |
|----------|----------|--------------|---------------------------------------------------|
| `admin`  | `admin`  | `ROLE_ADMIN` | Upload documents **and** chat / query RAG         |
| `user`   | `user`   | `ROLE_USER`  | Chat / query RAG and read own conversations       |

Authorization is enforced by real authentication: the caller's username becomes the
conversation owner (there is no client-supplied `X-User-Id` header).

- `POST /api/v1/documents` → `ROLE_ADMIN`
- `/api/v1/rag/**` → authenticated (`ROLE_USER` or `ROLE_ADMIN`)
- actuator health/info and the UI static assets → permit all

## Providers (stub, Ollama, OpenAI, Azure)

Embeddings and the LLM are each selected by one property; no image rebuild is
needed to switch.

| Provider | indexing-service (embeddings) | rag-service (embeddings + chat) | Needs                    |
|----------|-------------------------------|---------------------------------|--------------------------|
| `stub`   | yes (default)                 | yes (default)                   | nothing — fully offline  |
| `ollama` | yes                           | yes                             | local Ollama             |
| `openai` | yes                           | yes                             | `OPENAI_API_KEY`         |
| `azure`  | **no**                        | placeholder (fails on use)      | not implemented yet      |

Properties: `knowledge-platform.embedding.provider` (both services) and
`knowledge-platform.llm.provider` (rag-service). As env vars:

| Setting            | indexing-service                         | rag-service          |
|--------------------|------------------------------------------|----------------------|
| embedding provider | `KNOWLEDGE_PLATFORM_EMBEDDING_PROVIDER`  | `EMBEDDING_PROVIDER` |
| LLM provider       | —                                        | `LLM_PROVIDER`       |

> **Embedding parity.** indexing-service and rag-service MUST use the same
> embedding provider, model and dimensions (768), otherwise question vectors can't
> be compared with stored chunk vectors and retrieval returns nonsense. After
> switching the embedding provider, re-upload documents (or start clean with
> `docker compose -f compose.full.yaml down -v`). The LLM provider can be changed
> freely.

### Ollama (local, no keys)

Install [Ollama](https://ollama.com) and pull the models once:

```bash
ollama serve                     # if not already running; http://localhost:11434
ollama pull nomic-embed-text     # 768-dim embedding model (both services)
ollama pull llama3.2             # chat model (rag-service)
```

**Docker Compose** — add the `compose.ollama.yaml` overlay; the containers reach
the host's Ollama via `host.docker.internal`:

```bash
docker compose -f compose.full.yaml -f compose.ollama.yaml up --build
```

Pick another chat model with e.g. `OLLAMA_CHAT_MODEL=gpt-oss:20b docker compose ...`.

**Services individually** (see [Local development](#local-development)):

```bash
cd indexing-service
SPRING_PROFILES_ACTIVE=dev KNOWLEDGE_PLATFORM_EMBEDDING_PROVIDER=ollama ../mvnw spring-boot:run

cd rag-service
EMBEDDING_PROVIDER=ollama LLM_PROVIDER=ollama ../mvnw spring-boot:run
```

Optional overrides: `OLLAMA_BASE_URL` (default `http://localhost:11434`),
`OLLAMA_EMBEDDING_MODEL` (default `nomic-embed-text`), `OLLAMA_CHAT_MODEL`
(rag-service, default `llama3.2`). Models are never auto-pulled.

### OpenAI (platform.openai.com)

The key is supplied only via the environment — never commit it.

**Docker Compose:**

```bash
docker compose -f compose.full.yaml -f compose.openai.yaml up --build
```

`EMBEDDING_PROVIDER` is passed to both indexing- and rag-service, so parity is kept.

**Services individually:**

```bash
export OPENAI_API_KEY=sk-...

cd indexing-service
SPRING_PROFILES_ACTIVE=dev KNOWLEDGE_PLATFORM_EMBEDDING_PROVIDER=openai ../mvnw spring-boot:run

cd rag-service
EMBEDDING_PROVIDER=openai LLM_PROVIDER=openai ../mvnw spring-boot:run
# or: SPRING_PROFILES_ACTIVE=local ../mvnw spring-boot:run   (same, via application-local.properties)
```

Defaults: `OPENAI_EMBEDDING_MODEL=text-embedding-3-small` truncated to
`OPENAI_EMBEDDING_DIMENSIONS=768` (Matryoshka) to match the pgvector column;
`OPENAI_CHAT_MODEL=gpt-4o-mini`.

### Azure OpenAI (placeholder — not functional yet)

rag-service has `azure` beans wired to `AZURE_OPENAI_*` env vars, but they are
inert placeholders: the app starts, and the first embedding/chat call fails with a
clear "placeholder" error. indexing-service has no `azure` provider at all. To
enable Azure, add the Spring AI Azure OpenAI starter to both services and replace
the bodies of `AzureOpenAiEmbeddingModel` / `AzureOpenAiChatLlm`. Once that is
done it would be started like this:

```bash
EMBEDDING_PROVIDER=azure \
LLM_PROVIDER=azure \
AZURE_OPENAI_ENDPOINT=https://<resource>.openai.azure.com \
AZURE_OPENAI_API_KEY=<key> \
AZURE_OPENAI_CHAT_DEPLOYMENT=<chat-deployment> \
AZURE_OPENAI_EMBEDDING_DEPLOYMENT=<embedding-deployment> \
docker compose -f compose.full.yaml up --build
```

## Service boundary trade-off (read-only chunk access)

`rag-service` reads the `indexing_service.document_chunks` table (owned by
indexing-service) directly for vector retrieval. This is a deliberate, documented,
**read-only** coupling for the demo — rag-service never writes to, migrates, or owns
that table, and both services pin the same 768-dim / cosine settings.

The clean-architecture alternative (a seam left as a TODO): indexing-service could
expose a `/search` API or publish a read model that rag-service consumes, removing
the shared-table read. Not built in this phase.

The shared **uploads** named volume mounted into both document- and indexing-service
is the one other piece of local coupling, called out in `compose.full.yaml`.

## Upload flow

1. Client uploads a multipart file to `POST /api/v1/documents`
2. The service validates content type and size
3. The file is stored on disk
4. `documents` and `outbox_events` are written in one PostgreSQL transaction
5. A background outbox publisher sends `DocumentUploadedV1` to Kafka

## Transactional outbox

The service does not publish Kafka messages inside the upload transaction. Instead, it stores an outbox row with the document data. A scheduled publisher later claims unpublished rows, sends them to Kafka, and sets `published_at` after success.

This gives at-least-once delivery. Consumers must be idempotent.

## Local development

### With Docker Compose

Start infrastructure from the monorepo root:

```bash
docker compose -f compose.yaml up -d
```

Run each service from its own application directory (separate terminals). The
`dev` profile points document- and indexing-service at the infrastructure from
`compose.yaml` (Kafka on `localhost:9092`):

```bash
cd document-service
SPRING_PROFILES_ACTIVE=dev ../mvnw spring-boot:run    # :8080

cd indexing-service
SPRING_PROFILES_ACTIVE=dev ../mvnw spring-boot:run    # consumes Kafka, no HTTP API

cd rag-service
../mvnw spring-boot:run                               # :8082 (no Kafka needed)
```

Add the provider env vars from [Providers](#providers-stub-ollama-openai-azure)
to run against Ollama or OpenAI instead of the stubs.

### Profiles

- default: application runtime settings
- `dev` (document-, indexing-service): local PostgreSQL + Kafka from `compose.yaml`
- `local` (rag-service): OpenAI for both embeddings and chat (key via `OPENAI_API_KEY`)

### Embedding dimensions

`knowledge-platform.embedding.dimensions` (768) must match the selected model and
the `document_chunks.embedding` pgvector column in both services
(`nomic-embed-text` is natively 768; `text-embedding-3-small` is truncated to 768).

## API example

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@architecture.pdf;type=application/pdf"
```

Response: `202 Accepted`

```json
{
  "id": "uuid",
  "fileName": "architecture.pdf",
  "status": "UPLOADED",
  "createdAt": "2026-07-29T..."
}
```

## Database

### `documents`

- `id`
- `file_name`
- `content_type`
- `size`
- `status`
- `storage_key`
- `created_at`
- `updated_at`
- `version`

### `outbox_events`

- `id`
- `aggregate_id`
- `aggregate_type`
- `event_type`
- `payload`
- `created_at`
- `published_at`
- `claimed_by`
- `claimed_until`
- `attempts`

## Kafka topic

- Topic: `knowledge.documents.v1`
- Key: `documentId`
- Payload: `DocumentUploadedV1`

## Testing

Run the full suite:

```bash
./mvnw test
```

Tests use Testcontainers for PostgreSQL (pgvector) and Kafka. Notable coverage:

- Retrieval ranking over seeded pgvector chunks (`rag-service`)
- Stub-embedding parity between indexing-service and rag-service (identical text →
  identical 768-dim vector, so query and stored embeddings are comparable)
- Stub LLM stream: stable answer, cited source ids, multiple DELTAs
- Chat NDJSON ordering (META → SOURCES → DELTA+ → DONE) and message persistence
- Security: unauthenticated → 401/403; `ROLE_USER` cannot upload; conversation
  ownership isolation

## Known limitations

- Demo-grade security only: in-memory users, HTTP Basic, visible demo passwords —
  not production auth (no OAuth2/OIDC, user store, or password hashing at scale)
- Providers default to offline stubs; Ollama and OpenAI are opt-in; Azure OpenAI
  is a non-functional placeholder
- Outbox polling is intentionally simple and single-worker friendly
- No S3 storage, API gateway, hybrid/full-text search, or Kubernetes in this phase
- Consumers must tolerate duplicate events (at-least-once delivery)
- `rag-service` reads indexed chunks via a documented read-only table coupling
  (see "Service boundary trade-off")
