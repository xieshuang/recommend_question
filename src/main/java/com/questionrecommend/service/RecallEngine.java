package com.questionrecommend.service;

import com.questionrecommend.cache.RedisCacheService;
import com.questionrecommend.client.MilvusClientWrapper;
import com.questionrecommend.config.AppProperties;
import com.questionrecommend.model.RecommendMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 多路召回引擎 — 核心推荐流水线
 * <p>
 * <b>架构变更核心：</b>该服务替代了 Python similar.py 的 _recommend_multi_path() 方法，
 * 以及原来在 ES 内部由 Painless 脚本完成的混合评分。
 * <p>
 * 核心推荐流程（10 步）：
 * <ol>
 *   <li>解析推荐模式 + 权重（{@link RecommendMode#resolveWeights}）</li>
 *   <li>检查 Redis 缓存</li>
 *   <li>获取源题数据（Milvus get by id）</li>
 *   <li>并行发起 R0_knowledge + R1_vector 两路查询（虚拟线程）</li>
 *   <li>统一大池子融合（R0 路径奖励 +0.15）</li>
 *   <li>多维度加权精排（{@link RankingService#calculateScore}）</li>
 *   <li>SimHash + MD5 去重（{@link DeduplicationService}）</li>
 *   <li>缺量补充查询（最多 2 轮 R1 扩大召回）</li>
 *   <li>写入 Redis 缓存</li>
 *   <li>返回 TopK 结果</li>
 * </ol>
 */
@Service
public class RecallEngine {

    private static final Logger log = LoggerFactory.getLogger(RecallEngine.class);

    /** R0 知识路径奖励系数（等价 Python R0_BONUS = 0.15） */
    private static final double R0_BONUS = 0.15;

    /** 单路召回默认数量 */
    private static final int DEFAULT_PATH_SIZE = 50;

    /** 最大补充轮次 */
    private static final int MAX_SUPPLEMENT_ROUNDS = 2;

    /** 补充轮次增量系数 */
    private static final double SUPPLEMENT_EXPAND_FACTOR = 1.5;

    private final MilvusClientWrapper milvusClient;
    private final MilvusQueryService milvusQueryService;
    private final RankingService rankingService;
    private final DeduplicationService deduplicationService;
    private final RedisCacheService cacheService;
    private final AppProperties properties;
    private final ExecutorService virtualThreadExecutor;

    /** 路径名称到中文映射 */
    private static final Map<String, String> PATH_NAME_MAP = Map.of(
            "R0_knowledge", "知识点推荐",
            "R1_vector", "向量推荐",
            "R1_supplement", "补充推荐"
    );

    public RecallEngine(MilvusClientWrapper milvusClient,
                         MilvusQueryService milvusQueryService,
                         RankingService rankingService,
                         DeduplicationService deduplicationService,
                         RedisCacheService cacheService,
                         AppProperties properties,
                         @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.milvusClient = milvusClient;
        this.milvusQueryService = milvusQueryService;
        this.rankingService = rankingService;
        this.deduplicationService = deduplicationService;
        this.cacheService = cacheService;
        this.properties = properties;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * 多路召回推荐核心
     * <p>
     * 等价 Python: _recommend_multi_path()
     */
    public RecallResult recommend(String questionId, int size,
                                   List<String> disableQuestion,
                                   double simWeight, double tagWeight,
                                   double mainKlgWeight, double allKlgWeight,
                                   Integer difficulty, List<String> questionType,
                                   String recommendMode,
                                   List<String> mainLlmKnowledge,
                                   List<String> allLlmKnowledge,
                                   boolean useCache,
                                   Map<String, Object> preloadedSource) {
        long overallStartNs = System.nanoTime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();

        // ====== 1. 参数校验 ======
        if (questionId == null || questionId.isEmpty()) {
            return new RecallResult(questionId, false, Collections.emptyList(), 0, false, diagnostics);
        }
        String mode = recommendMode != null ? recommendMode : "custom";
        int targetSize = size > 0 ? size : 10;

        // ====== 2. 解析权重 ======
        RecommendMode.WeightResult weights = RecommendMode.resolveWeights(
                mode, simWeight, tagWeight, mainKlgWeight, allKlgWeight);
        double effSimWeight = weights.simWeight();
        double effTagWeight = weights.tagWeight();
        double effMainKlgWeight = weights.mainKlgWeight();
        double effAllKlgWeight = weights.allKlgWeight();
        RecommendMode effMode = weights.effectiveMode();
        String effModeName = effMode.getModeName();
        diagnostics.put("effective_mode", effModeName);

        // ====== 3. 检查 Redis 缓存 ======
        String sortedTypes = questionType != null
                ? questionType.stream().sorted().collect(Collectors.joining(","))
                : "";
        String cacheKey = null;
        if (useCache && properties.getCache().isEnabled()) {
            cacheKey = cacheService.getCacheKey(
                    questionId, targetSize, effModeName,
                    effSimWeight, effTagWeight, effMainKlgWeight, effAllKlgWeight,
                    difficulty, sortedTypes,
                    mainLlmKnowledge, allLlmKnowledge, disableQuestion);
            List<Map<String, Object>> cached = cacheService.get(cacheKey);
            if (cached != null) {
                log.debug("缓存命中: {}", cacheKey);
                diagnostics.put("cache_hit", true);
                return new RecallResult(questionId, true, cached, cached.size(), true, diagnostics);
            }
        }
        diagnostics.put("cache_hit", false);

        // ====== 4. 获取源题数据 ======
        Map<String, Object> source;
        if (preloadedSource != null && !preloadedSource.isEmpty()) {
            source = preloadedSource;
            diagnostics.put("source_preloaded", true);
        } else {
            source = milvusClient.queryById(questionId);
            if (source == null || source.isEmpty()) {
                log.warn("源题不存在: {}", questionId);
                diagnostics.put("source_found", false);
                return new RecallResult(questionId, false, Collections.emptyList(), 0, false, diagnostics);
            }
        }
        diagnostics.put("source_found", true);

        // 5. 提取特征向量和标量字段
        List<Float> targetVector = extractFloatVector(source);
        String targetSubject = Objects.toString(source.get("subjectTagCode"), null);
        String targetPhase = Objects.toString(source.get("phaseTagCode"), null);
        Long targetBaseTypeId = source.get("baseTypeId") instanceof Number
                ? ((Number) source.get("baseTypeId")).longValue() : null;

        @SuppressWarnings("unchecked")
        List<String> targetTags = (List<String>) source.getOrDefault("tagCodes", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<String> targetMainKlg = mainLlmKnowledge != null ? mainLlmKnowledge
                : (List<String>) source.getOrDefault("mainLlmKnowledge", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<String> targetAllKlg = allLlmKnowledge != null ? allLlmKnowledge
                : (List<String>) source.getOrDefault("allLlmKnowledge", Collections.emptyList());

        // knowledge_strict 模式降级检测
        if (effMode == RecommendMode.KNOWLEDGE_STRICT && targetMainKlg.isEmpty()) {
            log.warn("[推荐] {} 主知识点为空，knowledge_strict 自动降级为 balanced", questionId);
            RecommendMode.WeightResult fallbackWeights = RecommendMode.resolveWeights(
                    "balanced", simWeight, tagWeight, mainKlgWeight, allKlgWeight);
            effSimWeight = fallbackWeights.simWeight();
            effTagWeight = fallbackWeights.tagWeight();
            effMainKlgWeight = fallbackWeights.mainKlgWeight();
            effAllKlgWeight = fallbackWeights.allKlgWeight();
            effMode = fallbackWeights.effectiveMode();
            effModeName = effMode.getModeName();
            diagnostics.put("effective_mode", effModeName + "(降级)");
        }

        // 包含 ID 列表（用于过滤）
        List<String> effectiveDisableIds = new ArrayList<>();
        if (disableQuestion != null) effectiveDisableIds.addAll(disableQuestion);
        effectiveDisableIds.add(questionId); // 排除自身

        int pathSize = Math.max(targetSize * 3, DEFAULT_PATH_SIZE);

        // ====== 6. 并行发起两路查询 ======
        List<List<Map<String, Object>>> pathResults;
        try {
            pathResults = executeTwoPathQuery(
                    targetVector, targetTags, targetMainKlg, targetAllKlg,
                    targetSubject, targetPhase, targetBaseTypeId,
                    difficulty, questionType, effectiveDisableIds,
                    pathSize, effModeName);
        } catch (Exception e) {
            log.error("并行路径查询失败: {}", e.getMessage(), e);
            diagnostics.put("query_error", e.getMessage());
            return new RecallResult(questionId, false, Collections.emptyList(), 0, false, diagnostics);
        }

        // ====== 7. 统一大池子融合 ======
        Map<String, Map<String, Object>> mergedPool = new LinkedHashMap<>();
        Map<String, Double> pathBonuses = new LinkedHashMap<>();

        for (int i = 0; i < pathResults.size(); i++) {
            List<Map<String, Object>> hits = pathResults.get(i);
            String pathName = i == 0 ? "R0_knowledge" : "R1_vector";
            diagnostics.put(pathName + "_hits", hits.size());

            for (Map<String, Object> hit : hits) {
                String id = hit.get("_id") != null ? hit.get("_id").toString() : null;
                if (id == null) continue;

                // R0 路径奖励
                double bonus = "R0_knowledge".equals(pathName) ? R0_BONUS : 0.0;
                double currentScore = bonus;

                // 保留最高分版本
                Double existing = pathBonuses.get(id);
                if (existing == null || currentScore > existing) {
                    mergedPool.put(id, hit);
                    pathBonuses.put(id, currentScore);
                }
            }
        }

        log.debug("融合后大池子: {} 条", mergedPool.size());
        diagnostics.put("merged_pool_size", mergedPool.size());

        // ====== 8. 多维度加权精排 ======
        List<Map<String, Object>> ranked = rankCandidates(
                new ArrayList<>(mergedPool.values()), pathBonuses,
                source, targetTags, targetMainKlg, targetAllKlg,
                effSimWeight, effTagWeight, effMainKlgWeight, effAllKlgWeight,
                effMode, diagnostics);

        // ====== 9. SimHash + MD5 去重 ======
        List<Map<String, Object>> deduped = deduplicationService.dedup(source, ranked, targetSize);
        diagnostics.put("after_dedup", deduped.size());

        // ====== 10. 缺量补充查询 ======
        if (deduped.size() < targetSize) {
            Set<String> processedIds = new HashSet<>(effectiveDisableIds);
            for (Map<String, Object> item : deduped) {
                Object id = item.get("_id");
                if (id != null) processedIds.add(id.toString());
            }

            deduped = supplementRecall(
                    deduped, targetVector, targetTags, targetMainKlg, targetAllKlg,
                    targetSubject, targetPhase, targetBaseTypeId,
                    difficulty, questionType, new ArrayList<>(processedIds),
                    targetSize, effModeName, diagnostics);
        }

        // ====== 11. 截断到 TopK ======
        List<Map<String, Object>> result = deduped.size() > targetSize
                ? new ArrayList<>(deduped.subList(0, targetSize))
                : new ArrayList<>(deduped);

        // ====== 12. 写入 Redis 缓存 ======
        if (useCache && properties.getCache().isEnabled() && cacheKey != null && !result.isEmpty()) {
            cacheService.set(cacheKey, result, properties.getCache().getTtlSeconds());
        }

        long totalMs = (System.nanoTime() - overallStartNs) / 1_000_000L;
        diagnostics.put("total_elapsed_ms", totalMs);
        diagnostics.put("result_count", result.size());

        log.info("推荐完成: q={} result={}条 elapsed={}ms", questionId, result.size(), totalMs);
        return new RecallResult(questionId, true, result, result.size(), false, diagnostics);
    }

    // ====== 内部方法 ======

    /**
     * 并行发起 R0 和 R1 两路查询（虚拟线程）
     * <p>
     * 等价 Python: asyncio.gather(*tasks, return_exceptions=True)
     */
    private List<List<Map<String, Object>>> executeTwoPathQuery(
            List<Float> targetVector, List<String> targetTags,
            List<String> targetMainKlg, List<String> targetAllKlg,
            String targetSubject, String targetPhase,
            Long targetBaseTypeId, Integer difficulty,
            List<String> questionType, List<String> disableIds,
            int pathSize, String mode) throws Exception {

        Callable<List<Map<String, Object>>> r0Task = () ->
                milvusQueryService.executeR0Path(
                        targetVector, targetMainKlg, targetAllKlg,
                        targetSubject, targetPhase, targetBaseTypeId,
                        difficulty, questionType, disableIds, pathSize, mode);

        Callable<List<Map<String, Object>>> r1Task = () ->
                milvusQueryService.executeR1Path(
                        targetVector, targetSubject, targetPhase, targetBaseTypeId,
                        difficulty, questionType, disableIds, pathSize, mode);

        List<Future<List<Map<String, Object>>>> futures = virtualThreadExecutor.invokeAll(
                Arrays.asList(r0Task, r1Task));
        List<List<Map<String, Object>>> results = new ArrayList<>();
        for (Future<List<Map<String, Object>>> future : futures) {
            try {
                results.add(future.get(15, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                log.warn("路径查询超时");
                results.add(Collections.emptyList());
            } catch (Exception e) {
                log.warn("路径查询异常: {}", e.getMessage());
                results.add(Collections.emptyList());
            }
        }
        return results;
    }

    /**
     * 多维度加权精排
     * <p>
     * 等价 Python similar.py 中的精排逻辑。
     * knowledge_strict 模式下过滤 main_klg_score == 0 的题目。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rankCandidates(
            List<Map<String, Object>> candidates,
            Map<String, Double> pathBonuses,
            Map<String, Object> source,
            List<String> targetTags,
            List<String> targetMainKlg,
            List<String> targetAllKlg,
            double simWeight, double tagWeight,
            double mainKlgWeight, double allKlgWeight,
            RecommendMode mode,
            Map<String, Object> diagnostics) {

        List<Map<String, Object>> result = new ArrayList<>();
        int klgFilteredCount = 0;

        for (Map<String, Object> candidate : candidates) {
            String id = candidate.get("_id") != null ? candidate.get("_id").toString() : null;

            // 向量相似度（Milvus ANN 返回的归一化 cosine）
            double simScore = candidate.get("_normalizedScore") instanceof Number ns
                    ? ns.doubleValue() : 0.0;

            // 标签 Jaccard
            List<String> candTags = (List<String>) candidate.getOrDefault("tagCodes", Collections.emptyList());
            double tagScore = rankingService.jaccardSimilarity(targetTags, candTags);

            // 知识点 Jaccard
            List<String> candMainKlg = (List<String>) candidate.getOrDefault("mainLlmKnowledge", Collections.emptyList());
            double mainKlgScore = rankingService.jaccardSimilarity(targetMainKlg, candMainKlg);
            List<String> candAllKlg = (List<String>) candidate.getOrDefault("allLlmKnowledge", Collections.emptyList());
            double allKlgScore = rankingService.jaccardSimilarity(targetAllKlg, candAllKlg);

            // knowledge_strict 模式下过滤主知识点为 0 的题目
            if (mode == RecommendMode.KNOWLEDGE_STRICT && mainKlgScore == 0.0) {
                klgFilteredCount++;
                continue;
            }

            // 路径奖励
            double bonus = pathBonuses.getOrDefault(id, 0.0);

            // 综合得分
            double totalScore = rankingService.calculateScore(
                    simScore, tagScore, mainKlgScore, allKlgScore,
                    simWeight, tagWeight, mainKlgWeight, allKlgWeight)
                    + bonus;

            // 注入评分字段
            candidate.put("_totalScore", totalScore);
            candidate.put("_simScore", simScore);
            candidate.put("_tagScore", tagScore);
            candidate.put("_mainKlgScore", mainKlgScore);
            candidate.put("_allKlgScore", allKlgScore);

            result.add(candidate);
        }

        // 按综合得分降序排列
        result.sort((a, b) -> {
            double sa = ((Number) a.getOrDefault("_totalScore", 0.0)).doubleValue();
            double sb = ((Number) b.getOrDefault("_totalScore", 0.0)).doubleValue();
            return Double.compare(sb, sa);
        });

        if (klgFilteredCount > 0) {
            log.debug("knowledge_strict 过滤: {} 条", klgFilteredCount);
            diagnostics.put("knowledge_strict_filtered", klgFilteredCount);
        }

        return result;
    }

    /**
     * 缺量补充查询
     * <p>
     * 当去重后数量不足 targetSize 时，扩大 R1 向量路径召回范围追加补充。
     * 等价 Python similar.py 中的补充逻辑。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> supplementRecall(
            List<Map<String, Object>> current,
            List<Float> targetVector,
            List<String> targetTags,
            List<String> targetMainKlg,
            List<String> targetAllKlg,
            String targetSubject, String targetPhase,
            Long targetBaseTypeId, Integer difficulty,
            List<String> questionType, List<String> disableIds,
            int targetSize, String mode, Map<String, Object> diagnostics) {

        List<Map<String, Object>> result = new ArrayList<>(current);
        int supplementCount = 0;

        for (int round = 0; round < MAX_SUPPLEMENT_ROUNDS && result.size() < targetSize; round++) {
            int expandedSize = (int) (targetSize * SUPPLEMENT_EXPAND_FACTOR * (round + 1));

            List<Map<String, Object>> supplementary = milvusQueryService.executeR1Path(
                    targetVector, targetSubject, targetPhase, targetBaseTypeId,
                    difficulty, questionType, disableIds, expandedSize, mode);

            log.debug("第 {} 轮补充查询: 召回 {} 条", round + 1, supplementary.size());
            supplementCount += supplementary.size();

            // 标记补充路径
            for (Map<String, Object> hit : supplementary) {
                hit.put("_path", "R1_supplement");
            }

            // 去重后追加
            List<Map<String, Object>> newCandidates = deduplicationService.dedup(
                    null, supplementary, targetSize - result.size());

            // 对补充结果精排
            Map<String, Double> bonusMap = new LinkedHashMap<>();
            for (Map<String, Object> hit : newCandidates) {
                String id = hit.get("_id") != null ? hit.get("_id").toString() : null;
                if (id != null) bonusMap.put(id, 0.0);
            }

            List<Map<String, Object>> rankedNew = rankCandidates(
                    newCandidates, bonusMap, null,
                    targetTags, targetMainKlg, targetAllKlg,
                    0.4, 0.2, 0.25, 0.15,
                    RecommendMode.fromString(mode), diagnostics);

            // 合并并重新排序
            result.addAll(rankedNew);
            result.sort((a, b) -> {
                double sa = ((Number) a.getOrDefault("_totalScore", 0.0)).doubleValue();
                double sb = ((Number) b.getOrDefault("_totalScore", 0.0)).doubleValue();
                return Double.compare(sb, sa);
            });

            // 更新排除列表
            for (Map<String, Object> item : rankedNew) {
                Object id = item.get("_id");
                if (id != null) disableIds.add(id.toString());
            }
        }

        diagnostics.put("supplement_total", supplementCount);
        return result;
    }

    /**
     * 从源题 Map 中提取 float 向量
     */
    private List<Float> extractFloatVector(Map<String, Object> source) {
        Object vec = source.get("vector");
        if (vec instanceof List<?> list) {
            List<Float> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number num) {
                    result.add(num.floatValue());
                }
            }
            if (!result.isEmpty()) return result;
        }
        return Collections.emptyList();
    }

    // ====== 结果记录 ======

    /**
     * 推荐结果记录
     */
    public record RecallResult(String questionId, boolean success,
                                List<Map<String, Object>> data, int dataCount,
                                boolean fromCache, Map<String, Object> diagnostics) {}
}
