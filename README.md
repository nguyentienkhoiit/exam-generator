# exam-generator (Spring Boot + Spring AI Hybrid RAG)

Production-ready Hybrid RAG backend:

- PDF upload -> token chunking (overlap) -> Ollama embeddings -> PostgreSQL+pgvector vectors + Elasticsearch BM25 raw
  text
- Runtime: parallel vector + BM25 -> α fusion -> MMR diversification -> rerank -> DeepSeek (OpenAI-compatible) answer

## Prerequisites

- Docker + docker-compose
- Java 21
- Maven

## 1) Start infrastructure

```bash
docker compose up -d