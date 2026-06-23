package com.questionrecommend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 并发配置 — Java 21 虚拟线程
 * <p>
 * 等价 Python asyncio.gather 的并发模型。
 * 虚拟线程由 JVM 负责调度，当阻塞在 Milvus gRPC / Redis / HTTP 等 IO 操作时
 * 自动让出底层平台线程，实现与 asyncio 等价的高并发，无需信号量限流。
 * <p>
 * 架构对应关系：
 * <ul>
 *   <li>Python asyncio.gather → {@link ExecutorService#invokeAll} (虚拟线程)</li>
 *   <li>Python asyncio.Semaphore → 无需，虚拟线程本身即轻量</li>
 * </ul>
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 虚拟线程执行器 — 等价 Python asyncio 事件循环
     * <p>
     * Java 21 虚拟线程为轻量级用户态线程（百万级），每个任务创建新虚拟线程，
     * 在阻塞 IO 时自动 yield 底层平台线程，无需信号量限流。
     */
    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
