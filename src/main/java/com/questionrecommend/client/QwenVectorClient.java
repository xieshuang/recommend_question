package com.questionrecommend.client;

import com.questionrecommend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通义千问向量服务客户端 — 等价 Python qwen_client.py
 * <p>
 * 调用外部 HTTP 服务将文本转为 768 维向量。
 * Spring WebClient 替代 Python httpx/requests。
 */
@Component
public class QwenVectorClient {

    private static final Logger log = LoggerFactory.getLogger(QwenVectorClient.class);

    private final WebClient webClient;
    private final AppProperties properties;

    public QwenVectorClient(AppProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getVector().getUrl())
                .defaultHeader("Authorization", properties.getVector().getAuth())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 将文本转为向量
     * <p>
     * 等价 Python: embedding(text) → response.json()['vector']
     *
     * @param text 待向量化的文本
     * @return 768 维 float 向量
     */
    @SuppressWarnings("unchecked")
    public List<Float> textToVector(String text) {
        if (text == null || text.isEmpty()) {
            log.warn("向量化文本为空，返回空向量");
            return Collections.emptyList();
        }

        try {
            Map<String, Object> request = Map.of("text", text);

            Map<String, Object> response = webClient.post()
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response != null && response.containsKey("vector")) {
                Object vectorObj = response.get("vector");
                if (vectorObj instanceof List<?> list) {
                    List<Float> vector = new java.util.ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Number num) {
                            vector.add(num.floatValue());
                        }
                    }
                    return vector;
                }
            }
            log.warn("向量服务返回格式异常: {}", response);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("调用向量服务失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 批量文本转向量（简化版，循环调用单条接口）
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    public List<List<Float>> batchTextToVector(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        return texts.stream()
                .map(this::textToVector)
                .toList();
    }
}
