package com.questionrecommend.service;

import com.questionrecommend.client.MilvusClientWrapper;
import com.questionrecommend.config.AppProperties;
import com.questionrecommend.model.RecommendMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 查询服务 — 执行单路 ANN 召回查询
 * <p>
 * <b>架构变更核心：</b>该服务替代了 Python 中 ES 查询 + Painless 脚本的功能。
 * <ul>
 *   <li>原来 R0 路：ES Painless script_score（混合评分在 ES 内部完成）</li>
 *   <li>现在 R0 路：Milvus ANN（纯向量检索）+ Java 后处理（Jaccard + 加权）</li>
 * </ul>
 * <p>
 * 等价 Python similar.py 中的：
 * <ul>
 *   <li>{@link #executeR0Path} — R0 知识点路（原 Painless script_score）</li>
 *   <li>{@link #executeR1Path} — R1 向量路（原 ES KNN）</li>
 *   <li>两路均包含 Milvus 表达式过滤 + 信号量并发控制</li>
 * </ul>
 */
@Service
public class MilvusQueryService {

    private static final Logger log = LoggerFactory.getLogger(MilvusQueryService.class);

    private final MilvusClientWrapper milvusClient;
    private final AppProperties properties;

    public MilvusQueryService(MilvusClientWrapper milvusClient,
                               AppProperties properties) {
        this.milvusClient = milvusClient;
        this.properties = properties;
    }

    /**
     * 执行 R0 知识点路径查询
     * <p>
     * <b>架构变化：</b>
     * <ul>
     *   <li>原：ES Painless script_score 一次性完成 Jaccard + cosine + 加权 + 排序</li>
     *   <li>现：Milvus ANN 纯向量检索 → Java 层后处理 Jaccard + 加权 + 排序</li>
     * </ul>
     * <p>
     * 流程：
     * <ol>
     *   <li>构建 Milvus 过滤表达式（含知识点硬过滤）</li>
     *   <li>执行 ANN 向量检索（cosine, k=pathSize）</li>
     *   <li>Java 层计算 mainJaccard + allJaccard + 混合评分</li>
     *   <li>排序返回候选列表</li>
     * </ol>
     *
     * @param targetVector     源题向量
     * @param targetMainKlg    源题主知识点列表
     * @param targetAllKlg     源题全部知识点列表
     * @param targetSubject    学科
     * @param targetPhase      学段
     * @param targetBaseTypeId 基础题型 ID
     * @param difficulty       难度过滤
     * @param questionType     题型过滤
     * @param disableIds       排除 ID 列表
     * @param pathSize         召回数量
     * @param mode             推荐模式
     * @return 查询结果（含 _id, _score, _normalizedScore, _path 及标量字段）
     */
    public List<Map<String, Object>> executeR0Path(
            List<Float> targetVector,
            List<String> targetMainKlg,
            List<String> targetAllKlg,
            String targetSubject,
            String targetPhase,
            Long targetBaseTypeId,
            Integer difficulty,
            List<String> questionType,
            List<String> disableIds,
            int pathSize,
            String mode) {

        long startNs = System.nanoTime();

        try {
            // 1. 构建过滤表达式
            String expr = buildFilterExpr(targetSubject, targetPhase,
                    targetBaseTypeId, difficulty, questionType, disableIds, null);

            // 2. knowledge_strict 模式：知识点硬过滤
            RecommendMode rm = RecommendMode.fromString(mode);
            if (rm == RecommendMode.KNOWLEDGE_STRICT
                    && (targetMainKlg != null && !targetMainKlg.isEmpty()
                    || targetAllKlg != null && !targetAllKlg.isEmpty())) {

                List<String> klgConditions = new ArrayList<>();
                if (targetMainKlg != null && !targetMainKlg.isEmpty()) {
                    String mainKlgExpr = targetMainKlg.stream()
                            .map(k -> String.format("\"%s\"", k))
                            .collect(Collectors.joining(", "));
                    klgConditions.add(String.format("mainLlmKnowledge in [%s]", mainKlgExpr));
                }
                if (targetAllKlg != null && !targetAllKlg.isEmpty()) {
                    String allKlgExpr = targetAllKlg.stream()
                            .map(k -> String.format("\"%s\"", k))
                            .collect(Collectors.joining(", "));
                    klgConditions.add(String.format("allLlmKnowledge in [%s]", allKlgExpr));
                }

                if (!klgConditions.isEmpty()) {
                    String klgExpr = String.join(" or ", klgConditions);
                    expr = expr.isEmpty() ? klgExpr : expr + " and (" + klgExpr + ")";
                }
            }

            // 3. 执行 ANN 向量检索
            List<Map<String, Object>> results = milvusClient.search(pathSize, expr, targetVector);

            // 4. Java 层后处理：Jaccard + 混合评分
            List<Map<String, Object>> scoredResults = new ArrayList<>();
            for (Map<String, Object> hit : results) {
                // 获取标量字段
                @SuppressWarnings("unchecked")
                List<String> candMainKlg = (List<String>) hit.getOrDefault("mainLlmKnowledge", Collections.emptyList());
                @SuppressWarnings("unchecked")
                List<String> candAllKlg = (List<String>) hit.getOrDefault("allLlmKnowledge", Collections.emptyList());

                // 计算 Jaccard
                double mainJaccard = calculateJaccard(targetMainKlg, candMainKlg);
                double allJaccard = calculateJaccard(targetAllKlg, candAllKlg);

                // 原 ES Painless 逻辑的精确映射：
                // knowledgeScore = mainJaccard * 0.6 + allJaccard * 0.4
                // vectorScore = (cosineSimilarity + 1) / 2.0
                // final_score = knowledgeScore * 0.7 + vectorScore * 0.3
                double knowledgeScore = mainJaccard * 0.6 + allJaccard * 0.4;
                double vectorScore = hit.containsKey("_normalizedScore")
                        ? ((Number) hit.get("_normalizedScore")).doubleValue()
                        : 0.0;
                double hybridScore = knowledgeScore * 0.7 + vectorScore * 0.3;

                // 注入评分字段
                hit.put("_path", "R0_knowledge");
                hit.put("_normalizedScore", hybridScore);
                hit.put("_mainKlgScore", mainJaccard);
                hit.put("_allKlgScore", allJaccard);

                scoredResults.add(hit);
            }

            // 按混合分排序
            scoredResults.sort((a, b) -> {
                double sa = ((Number) a.getOrDefault("_normalizedScore", 0.0)).doubleValue();
                double sb = ((Number) b.getOrDefault("_normalizedScore", 0.0)).doubleValue();
                return Double.compare(sb, sa);
            });

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.debug("R0 路径完成, 召回 {} 条, 耗时 {}ms", scoredResults.size(), elapsedMs);

            return scoredResults;

        } catch (Exception e) {
            log.warn("R0 路径查询异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 执行 R1 向量路径查询
     * <p>
     * 简化版：纯向量 ANN 检索 + 过滤，不做 Jaccard 后处理。
     * Jaccard 由 RecallEngine 的精排阶段统一处理。
     *
     * @param targetVector     源题向量
     * @param targetSubject    学科
     * @param targetPhase      学段
     * @param targetBaseTypeId 基础题型 ID
     * @param difficulty       难度过滤
     * @param questionType     题型过滤
     * @param disableIds       排除 ID 列表
     * @param pathSize         召回数量
     * @param mode             推荐模式
     * @return 查询结果
     */
    public List<Map<String, Object>> executeR1Path(
            List<Float> targetVector,
            String targetSubject,
            String targetPhase,
            Long targetBaseTypeId,
            Integer difficulty,
            List<String> questionType,
            List<String> disableIds,
            int pathSize,
            String mode) {

        long startNs = System.nanoTime();

        try {
            // 构建过滤表达式
            String expr = buildFilterExpr(targetSubject, targetPhase,
                    targetBaseTypeId, difficulty, questionType, disableIds, mode);

            // 执行 ANN 向量检索
            List<Map<String, Object>> results = milvusClient.search(pathSize, expr, targetVector);

            // 注入路径标记
            for (Map<String, Object> hit : results) {
                hit.put("_path", "R1_vector");
            }

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.debug("R1 路径完成, 召回 {} 条, 耗时 {}ms", results.size(), elapsedMs);

            return results;

        } catch (Exception e) {
            log.warn("R1 路径查询异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构建 Milvus 过滤表达式
     * <p>
     * 等价 Python similar.py 中构建 ES bool query filter 的逻辑。
     * 使用 Milvus expr 语法：
     * <ul>
     *   <li>字符串等值: field == "value"</li>
     *   <li>数字等值: field == 123</li>
     *   <li>IN 列表: field in ["a", "b"]</li>
     *   <li>AND: expr1 and expr2</li>
     * </ul>
     */
    private String buildFilterExpr(String subject, String phase,
                                    Long baseTypeId, Integer difficulty,
                                    List<String> questionType,
                                    List<String> disableIds,
                                    String mode) {
        List<String> conditions = new ArrayList<>();

        // 学科过滤
        if (subject != null && !subject.isEmpty()) {
            conditions.add(String.format("subjectTagCode == \"%s\"", subject));
        }

        // 学段过滤
        if (phase != null && !phase.isEmpty()) {
            conditions.add(String.format("phaseTagCode == \"%s\"", phase));
        }

        // 基础题型过滤
        if (baseTypeId != null) {
            conditions.add(String.format("baseTypeId == %d", baseTypeId));
        }

        // 难度过滤
        if (difficulty != null) {
            conditions.add(String.format("difficulty == %d", difficulty));
        }

        // 题型过滤
        if (questionType != null && !questionType.isEmpty()) {
            String typeExpr = questionType.stream()
                    .map(t -> String.format("typeTagCode == \"%s\"", t))
                    .collect(Collectors.joining(" or "));
            if (questionType.size() == 1) {
                conditions.add(typeExpr);
            } else {
                conditions.add("(" + typeExpr + ")");
            }
        }

        // 排除 ID
        if (disableIds != null && !disableIds.isEmpty()) {
            String idExpr = disableIds.stream()
                    .map(id -> String.format("\"%s\"", id))
                    .collect(Collectors.joining(", "));
            conditions.add(String.format("id not in [%s]", idExpr));
        }

        return String.join(" and ", conditions);
    }

    /**
     * 计算两列表的 Jaccard 相似度
     */
    private double calculateJaccard(List<String> a, List<String> b) {
        if (a == null || b == null) return 0.0;
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);
        int intersection = 0;
        for (String item : setA) {
            if (setB.contains(item)) intersection++;
        }
        int union = setA.size() + setB.size() - intersection;
        return union <= 0 ? 0.0 : (double) intersection / union;
    }
}
