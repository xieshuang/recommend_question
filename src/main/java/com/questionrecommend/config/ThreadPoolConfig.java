package com.questionrecommend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 并发配置 — 虚拟线程 + 信号量
 * <p>
 * 等价 Python asyncio.Semaphore + asyncio.gather 的并发控制。
 * Java 21 虚拟线程直接替代 async/await 模型。
 * <p>
 * 架构对应关系：
 * <ul>
 *   <li>Python asyncio.gather → Java {@link ExecutorService#invokeAll} (虚拟线程)</li>
 *   <li>Python asyncio.Semaphore(10) → {@link Semaphore}(10)</li>
 *   <li>Python asyncio.Semaphore(3) → batchChunkSemaphore(3)</li>
 * </ul>
 */
@Configuration
public class ThreadPoolConfig {

    private final AppProperties properties;

    public ThreadPoolConfig(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * 异步任务执行器 — 等价 Python asyncio 事件循环
     * <p>
     * 使用 cached thread pool 替代 Java 21 虚拟线程（兼容 Java 17）。
     * 配合 Semaphore 控制并发，效果等价于 asyncio.gather + Semaphore。
     */
    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newCachedThreadPool();
    }

    /**
     * Milvus 并发信号量 — 等价 _es_semaphore
     * <p>
     * 限制并发 Milvus 查询数量，避免过度占用连接和 Milvus 服务端资源。
     */
    @Bean
    public Semaphore milvusSemaphore() {
        return new Semaphore(properties.getConcurrency().getMilvusSemaphore());
    }

    /**
     * 批量接口信号量 — 等价 _batch_es_semaphore
     */
    @Bean
    public Semaphore batchMilvusSemaphore() {
        return new Semaphore(properties.getConcurrency().getBatchMilvusSemaphore());
    }

    /**
     * 批量接口 chunk 间并发信号量 — 等价 _batch_chunk_semaphore
     * <p>
     * 限制同时处理的 chunk 数，避免过大的瞬时并发压垮下游服务。
     */
    @Bean
    public Semaphore batchChunkSemaphore() {
        return new Semaphore(properties.getConcurrency().getBatchChunkSemaphore());
    }
}
