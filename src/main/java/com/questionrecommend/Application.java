package com.questionrecommend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.questionrecommend.config.AppProperties;

/**
 * 题目相似推荐引擎 (Java + Milvus 版) — Spring Boot 入口
 * <p>
 * 等价 Python 版本的 main.py，负责：
 * 1. 启动 Spring Boot 应用
 * 2. 注册全局配置类 {@link AppProperties}
 * 3. 自动扫描并加载所有组件（Controller/Service/Config）
 * <p>
 * 架构变更：ES → Milvus，评分位置从 DB 层迁移到 Java 应用层
 * 详见: Java+Milvus改造可行性评估.md
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
