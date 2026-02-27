package com.khoinguyen.exam.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Bean(destroyMethod = "close")
    public RestClient elasticRestClient(
            @Value("${exam.elasticsearch.url}") String url,
            @Value("${exam.elasticsearch.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${exam.elasticsearch.socket-timeout-ms}") int socketTimeoutMs
    ) {
        URI uri = URI.create(url);
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        log.info("event=elasticsearch_client_config url={} connectTimeoutMs={} socketTimeoutMs={}",
                url, connectTimeoutMs, socketTimeoutMs);

        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(rcb -> rcb
                        .setConnectTimeout(connectTimeoutMs)
                        .setConnectionRequestTimeout(connectTimeoutMs)
                        .setSocketTimeout(socketTimeoutMs));

        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    /**
     * Dedicated executor for parallel hybrid retrieval (vector + BM25).
     * Bounded fixed pool improves tail latency predictability under load.
     */
    @Bean
    public Executor hybridSearchExecutor() {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        log.info("event=hybrid_executor_config threads={}", threads);
        return Executors.newFixedThreadPool(threads);
    }
}