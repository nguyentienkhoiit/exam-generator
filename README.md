# Exam Generator (Spring Boot + Spring AI Hybrid RAG)

Backend service for generating English exam drafts from PDF materials using a **Hybrid RAG** pipeline:
- Dense retrieval (pgvector)
- Lexical retrieval (Elasticsearch BM25)
- Hybrid fusion + optional MMR diversification
- LLM generates schema-valid exam draft output (JSON)

---

## Features

- Upload PDF (textbook/notes) + prompt (topic, difficulty, number of questions)
- Ingest pipeline: extract text → clean → chunk (token-based overlap) → embed → store/index
- Retrieval pipeline:
  - vector similarity search (pgvector)
  - BM25 search (Elasticsearch)
  - α-fusion merge
  - optional MMR for diversity
- Generation: produce exam draft as structured JSON response
- Experiment runner (profile `experiments`) to output:
  - `exp1_chunking.csv`
  - `exp2_retrieval.csv`
  - (optional) `exp3_runs.csv`, `exp3_summary.csv`

---

## Tech Stack

- Java 21 + Spring Boot
- Spring AI (OpenAI-compatible chat model)
- PostgreSQL + pgvector (dense vector store)
- Elasticsearch (BM25 lexical search)
- Docker Compose for infra

---

## Prerequisites

- Java 21
- Docker + Docker Compose
- Maven (or `./mvnw` if wrapper exists)

---

## 1) Start infrastructure

From project root:

```bash
docker compose up -d
docker compose ps
```
## 2) Run experiments (Spring Boot profile)

Run directly with Maven:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=experiments