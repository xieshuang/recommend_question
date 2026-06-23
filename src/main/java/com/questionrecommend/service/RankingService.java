package com.questionrecommend.service;

import com.questionrecommend.config.GlobalVars;
import com.questionrecommend.util.JaccardUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 精排服务 — 多维度加权打分与过滤
 * <p>
 * <b>架构变更核心：</b>该服务替代了原来在 ES Painless 脚本中完成的所有评分逻辑。
 * 原来由 ES + Painless 完成的混合评分，现在全部在 Java 应用层完成。
 * <p>
 * 等价 Python similar.py 中的精排逻辑：
 * <ul>
 *   <li>{@link #calculateScore} — 综合得分计算（等价 Python calculateScore）</li>
 *   <li>{@link #normalizeScore} — cosine 归一化 [0,1]（等价 Python normalize_similarity_score）</li>
 *   <li>{@link #isOutOfChapter} — 章节超纲过滤（等价 Python out_of_chapter）</li>
 *   <li>{@link #jaccardSimilarity} — Jaccard 相似度（等价 Python calculate_jaccard_similarity_optimized）</li>
 * </ul>
 * <p>
 * Jaccard 相似度计算委托给 {@link JaccardUtils}。
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    private final GlobalVars globalVars;

    public RankingService(GlobalVars globalVars) {
        this.globalVars = globalVars;
    }

    /**
     * 计算综合得分（加权求和）
     * <p>
     * 等价 Python: calculateScore(SimilarScore, SimilarWeight)
     * <pre>
     * total = simWeight * simScore + tagWeight * tagScore
     *       + mainKlgWeight * mainKlgScore + allKlgWeight * allKlgScore
     * </pre>
     */
    public double calculateScore(double simScore, double tagScore,
                                  double mainKlgScore, double allKlgScore,
                                  double simWeight, double tagWeight,
                                  double mainKlgWeight, double allKlgWeight) {
        return simWeight * simScore + tagWeight * tagScore
                + mainKlgWeight * mainKlgScore + allKlgWeight * allKlgScore;
    }

    /**
     * 归一化相似度分数
     * <p>
     * 等价 Python normalize_similarity_score(rawScore, scoreSource)
     * <ul>
     *   <li>Cosine 相似度: [-1, 1] → [0, 1]（线性映射）</li>
     *   <li>Milvus score: [0, 2] 距离 → [0, 1] 相似度（1 - distance/2）</li>
     * </ul>
     */
    public double normalizeScore(double rawScore, String scoreSource) {
        if ("cosine".equalsIgnoreCase(scoreSource)) {
            // Cosine: [-1, 1] → [0, 1]
            return (rawScore + 1.0) / 2.0;
        }
        if ("milvus".equalsIgnoreCase(scoreSource)) {
            // Milvus cosine distance: [0, 2] → similarity: distance=0 → 1.0, distance=2 → 0.0
            // 但 Milvus 返回的 score 已经是 1 - distance（即 similarity），直接 clamp
            return Math.max(0.0, Math.min(1.0, rawScore));
        }
        // 默认 clamp 到 [0, 1]
        return Math.max(0.0, Math.min(1.0, rawScore));
    }

    /**
     * 章节超纲过滤 — 等价 Python out_of_chapter(a, b)
     * <p>
     * 判断候选题目是否超出源题的章节排序范围。
     * 通过 GlobalVars 中加载的 tagCode_map 获取章节索引。
     *
     * @param sourceTagList    源题的 tagList（含 tagType=10 的章节标签）
     * @param candidateTagList 候选题的 tagList
     * @return true 表示超纲（应过滤）
     */
    public boolean isOutOfChapter(List<Map<String, Object>> sourceTagList,
                                   List<Map<String, Object>> candidateTagList) {
        try {
            Map<String, Integer> tagCodeMap = globalVars.getTagCodeMap();
            if (sourceTagList == null || candidateTagList == null) return false;
            if (tagCodeMap == null || tagCodeMap.isEmpty()) return false;

            // 提取章节标签（tagType == 10）
            List<Map<String, Object>> srcChapters = sourceTagList.stream()
                    .filter(tag -> tag != null && Integer.valueOf(10).equals(tag.get("tagType")))
                    .collect(Collectors.toList());
            List<Map<String, Object>> candChapters = candidateTagList.stream()
                    .filter(tag -> tag != null && Integer.valueOf(10).equals(tag.get("tagType")))
                    .collect(Collectors.toList());

            if (srcChapters.isEmpty() || candChapters.isEmpty()) return false;

            // 查找源题和候选题在 tagCodeMap 中的索引
            Integer srcIndex = null;
            for (Map<String, Object> tag : srcChapters) {
                String tagCode = Objects.toString(tag.get("tagCode"), null);
                if (tagCode != null && tagCodeMap.containsKey(tagCode)) {
                    srcIndex = tagCodeMap.get(tagCode);
                    break;
                }
            }

            Integer candIndex = null;
            for (Map<String, Object> tag : candChapters) {
                String tagCode = Objects.toString(tag.get("tagCode"), null);
                if (tagCode != null && tagCodeMap.containsKey(tagCode)) {
                    candIndex = tagCodeMap.get(tagCode);
                    break;
                }
            }

            if (srcIndex == null || candIndex == null) return false;

            // 候选题索引 > 源题索引 → 超纲
            return candIndex > srcIndex;
        } catch (Exception e) {
            log.warn("章节超纲判断异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 计算两列表的 Jaccard 相似度
     * <p>
     * 等价 Python: calculate_jaccard_similarity_optimized()
     */
    public double jaccardSimilarity(List<String> listA, List<String> listB) {
        if (listA == null || listB == null) {
            return 0.0;
        }
        Set<String> setA = new HashSet<>(listA);
        Set<String> setB = new HashSet<>(listB);
        return JaccardUtils.calculateJaccardOptimized(setA, setB);
    }
}
