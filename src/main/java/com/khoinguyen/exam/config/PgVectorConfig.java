package com.khoinguyen.exam.config;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PgVectorConfig {

    /**
     * Vector model: Ollama embedding model (auto-configured by Spring AI).
     * Vector store: PgVector.
     */
    @Bean(name = "ollamaVectorStore")
    public VectorStore ollamaVectorStore(@Qualifier("vectorStore") VectorStore vectorStore) {
        return vectorStore;
    }
}
