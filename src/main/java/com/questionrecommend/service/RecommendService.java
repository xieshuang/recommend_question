package com.questionrecommend.service;

import com.questionrecommend.cache.RedisCacheService;
import com.questionrecommend.config.AppProperties;
import com.questionrecommend.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 推荐编排服务 — 等价 Python similar.py 的路由处理函数
 * <p>
 * 负责请求分发、批量并发控制、结果汇总。
 * 核心推荐逻辑委托给 {@link RecallEngine}。
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final RecallEngine recallEngine;
    private final RedisCacheService cacheService;
    private final AppProperties properties;
    private final ExecutorService virtualThreadExecutor;

    public RecommendService(RecallEngine recallEngine,
                             RedisCacheService cacheService,
                             AppProperties properties,
                             @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.recallEngine = recallEngine;
        this.cacheService = cacheService;
        this.properties = properties;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * 单条推荐
     * <p>
     * 等价 Python: recommend_questions() 路由处理器
     */
    public ApiResponse<Object> recommend(RecommendRequest request) {
        if (request == null || request.getQuestionId() == null) {
            return ApiResponse.error(400, "questionId 不能为空");
        }

        long startTime = System.currentTimeMillis();
        try {
            RecallEngine.RecallResult result = recallEngine.recommend(
                    request.getQuestionId(),
                    request.getSize(),
                    request.getDisableQuestion(),
                    request.getSimWeight(),
                    request.getTagWeight(),
                    request.getMainKlgWeight(),
                    request.getAllKlgWeight(),
                    request.getDifficulty(),
                    request.getQuestionType(),
                    request.getMode(),
                    request.getMainLlmKnowledge(),
                    request.getAllLlmKnowledge(),
                    request.isUseCache(),
                    null);

            if (!result.success()) {
                return ApiResponse.error(500, "推荐失败: " + result.questionId());
            }

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("questionId", result.questionId());
            responseData.put("data", result.data());
            responseData.put("count", result.dataCount());
            responseData.put("fromCache", result.fromCache());
            responseData.put("elapsedMs", System.currentTimeMillis() - startTime);

            ApiResponse<Object> response = ApiResponse.success(responseData);
            if (result.diagnostics() != null) {
                result.diagnostics().forEach(response::withDiagnostic);
            }
            return response;

        } catch (Exception e) {
            log.error("单条推荐异常: {}", e.getMessage(), e);
            return ApiResponse.error(500, "推荐服务内部错误: " + e.getMessage());
        }
    }

    /**
     * 批量推荐
     * <p>
     * 等价 Python: recommend_questions_batch()
     * 流程：
     * 1. 分组并行处理（chunk_size, chunk_semaphore）
     * 2. 汇总统计
     */
    public ApiResponse<Object> recommendBatch(BatchRecommendRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            return ApiResponse.error(400, "题目 ID 列表不能为空");
        }

        int chunkSize = properties.getConcurrency().getBatchChunkSize();
        List<String> ids = request.getIds();
        long startTime = System.currentTimeMillis();

        List<RecallEngine.RecallResult> allResults = Collections.synchronizedList(new ArrayList<>());

        // 分组并行处理
        for (int i = 0; i < ids.size(); i += chunkSize * 3) {
            int endIdx = Math.min(i + chunkSize * 3, ids.size());
            List<String> groupIds = ids.subList(i, endIdx);

            List<Future<Void>> futures = new ArrayList<>();
            for (int j = 0; j < groupIds.size(); j += chunkSize) {
                int chunkEnd = Math.min(j + chunkSize, groupIds.size());
                List<String> chunkIds = groupIds.subList(j, chunkEnd);

                futures.add(virtualThreadExecutor.submit(() -> {
                    for (String id : chunkIds) {
                        try {
                            RecallEngine.RecallResult result = recallEngine.recommend(
                                    id, request.getSize(),
                                    request.getDisableQuestion(),
                                    request.getSimilarityWeight(),
                                    request.getTagWeight(),
                                    request.getMainKlgWeight(),
                                    request.getAllKlgWeight(),
                                    request.getDifficulty(),
                                    request.getQuestionType(),
                                    request.getRecommendMode(),
                                    null, null,
                                    request.isUseCache(), null);
                            allResults.add(result);
                        } catch (Exception e) {
                            log.warn("批量推荐单项失败: {} -> {}", id, e.getMessage());
                            allResults.add(new RecallEngine.RecallResult(
                                    id, false, Collections.emptyList(), 0, false, null));
                        }
                    }
                    return null;
                }));
            }

            for (Future<Void> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("批量处理 chunk 超时");
                } catch (Exception e) {
                    log.warn("批量处理 chunk 异常: {}", e.getMessage());
                }
            }
        }

        // 汇总统计
        long elapsedMs = System.currentTimeMillis() - startTime;
        int successCount = (int) allResults.stream().filter(RecallEngine.RecallResult::success).count();

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("totalRequest", ids.size());
        responseData.put("successCount", successCount);
        responseData.put("failedCount", ids.size() - successCount);
        responseData.put("elapsedMs", elapsedMs);

        List<Map<String, Object>> resultsList = new ArrayList<>();
        for (RecallEngine.RecallResult result : allResults) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", result.questionId());
            item.put("success", result.success());
            item.put("data", result.data());
            item.put("count", result.dataCount());
            item.put("fromCache", result.fromCache());
            resultsList.add(item);
        }
        responseData.put("results", resultsList);

        log.info("批量推荐完成: total={}, success={}, elapsed={}ms", ids.size(), successCount, elapsedMs);
        return ApiResponse.success(responseData);
    }

    /**
     * 带数据推荐 — 等价 Python recommendWithDataRouter
     */
    public ApiResponse<Object> recommendWithData(SimilarWithDataRequest request) {
        if (request == null || request.getSourceData() == null) {
            return ApiResponse.error(400, "源题数据不能为空");
        }

        long startTime = System.currentTimeMillis();
        try {
            Object idObj = request.getSourceData().get("id");
            String questionId = idObj != null ? idObj.toString() : null;
            if (questionId == null) {
                return ApiResponse.error(400, "源题数据中缺少 id 字段");
            }

            RecallEngine.RecallResult result = recallEngine.recommend(
                    questionId,
                    request.getSize(),
                    request.getDisableQuestion(),
                    request.getSimWeight(),
                    request.getTagWeight(),
                    request.getMainKlgWeight(),
                    request.getAllKlgWeight(),
                    request.getDifficulty(),
                    request.getQuestionType(),
                    request.getMode(),
                    request.getMainLlmKnowledge(),
                    request.getAllLlmKnowledge(),
                    request.isUseCache(),
                    request.getSourceData());

            if (!result.success()) {
                return ApiResponse.error(500, "推荐失败: " + result.questionId());
            }

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("questionId", result.questionId());
            responseData.put("data", result.data());
            responseData.put("count", result.dataCount());
            responseData.put("fromCache", result.fromCache());
            responseData.put("elapsedMs", System.currentTimeMillis() - startTime);

            ApiResponse<Object> response = ApiResponse.success(responseData);
            if (result.diagnostics() != null) {
                result.diagnostics().forEach(response::withDiagnostic);
            }
            return response;

        } catch (Exception e) {
            log.error("带数据推荐异常: {}", e.getMessage(), e);
            return ApiResponse.error(500, "推荐服务内部错误: " + e.getMessage());
        }
    }
}
