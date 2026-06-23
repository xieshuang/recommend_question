package com.questionrecommend.service;

import com.questionrecommend.util.SimHashUtils;
import com.questionrecommend.util.TextCleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 去重服务 — MD5 哈希 + SimHash 近似去重
 * <p>
 * 等价 Python deduplication.py 的 DuplicationRemover 类。
 * <p>
 * 两级策略：
 * <ol>
 *   <li><b>MD5 精确去重</b> — 清洗后 title 完全相同的 O(1) 过滤</li>
 *   <li><b>SimHash 64 位</b> — 汉明距离阈值 5，覆盖 ~90% 相似度变式题</li>
 * </ol>
 * <p>
 * 与旧版 Levenshtein 对比：
 * <ul>
 *   <li>Levenshtein: O(L²)，单次比较 1~5ms</li>
 *   <li>SimHash: O(L) 分词 + O(N·64) 哈希，单次比较 &lt;0.01ms</li>
 *   <li>整体去重阶段性能提升 100x+</li>
 * </ul>
 */
@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);

    /** SimHash 汉明距离阈值（对应 ~90% 文本相似度） */
    private final int hammingThreshold = 5;

    /**
     * 两级去重 — 等价 Python deduplicator.dup(source, candidates, topK)
     * <p>
     * 从候选题目列表中选择不与源题目重复的前 topK 个题目，
     * 并确保结果列表中的题目彼此之间也不重复。
     *
     * @param source     源题目 Map（含 _id, title 等字段）
     * @param candidates 候选题目列表
     * @param topK       返回的最大结果数量
     * @return 不重复的前 topK 个题目列表
     */
    public List<Map<String, Object>> dedup(Map<String, Object> source,
                                            List<Map<String, Object>> candidates,
                                            int topK) {
        long startTime = System.currentTimeMillis();
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        String sourceId = source != null ? Objects.toString(source.get("_id"), "unknown") : "unknown";

        // 1. 源题指纹
        Fingerprint sourceFp = computeFingerprint(source);
        int sourceThreshold = adaptiveThreshold(sourceFp.cleanedText.length());

        // 2. 去重处理
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seenMd5 = new HashSet<>();
        List<Long> seenHashes = new ArrayList<>();
        int duplicateCount = 0;

        for (Map<String, Object> candidate : candidates) {
            if (result.size() >= topK) break;

            Fingerprint candFp = computeFingerprint(candidate);
            int threshold = Math.min(sourceThreshold, adaptiveThreshold(candFp.cleanedText.length()));

            // L1: MD5 精确去重
            if (!candFp.md5.isEmpty()
                    && (candFp.md5.equals(sourceFp.md5) || seenMd5.contains(candFp.md5))) {
                duplicateCount++;
                continue;
            }

            // L2: SimHash 近似去重
            // 与源题比较
            if (candFp.simhash != 0 && sourceFp.simhash != 0
                    && SimHashUtils.hammingDistance(candFp.simhash, sourceFp.simhash) < threshold) {
                duplicateCount++;
                continue;
            }

            // 与已选结果比较
            boolean isDup = false;
            for (long prevHash : seenHashes) {
                if (SimHashUtils.hammingDistance(candFp.simhash, prevHash) < threshold) {
                    isDup = true;
                    duplicateCount++;
                    break;
                }
            }

            if (!isDup) {
                result.add(candidate);
                if (!candFp.md5.isEmpty()) seenMd5.add(candFp.md5);
                if (candFp.simhash != 0) seenHashes.add(candFp.simhash);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[去重] 完成: 源题={}, 输入{}条 -> 输出{}条, 过滤{}条重复, 耗时{}ms",
                sourceId, candidates.size(), result.size(), duplicateCount, elapsed);

        return result;
    }

    // ====== 内部辅助方法 ======

    /**
     * 指纹数据结构
     */
    private record Fingerprint(String md5, long simhash, String cleanedText) {}

    /**
     * 计算题目 Map 的指纹
     */
    private Fingerprint computeFingerprint(Map<String, Object> obj) {
        if (obj == null) return new Fingerprint("", 0L, "");
        String text = TextCleanUtils.extractTextFromMap(obj);
        String cleaned = TextCleanUtils.cleanText(text);
        if (cleaned.isEmpty()) return new Fingerprint("", 0L, cleaned);
        String md5 = TextCleanUtils.md5Hex(cleaned);
        List<String> tokens = TextCleanUtils.tokenize(cleaned);
        long sh = SimHashUtils.calculateSimHash(tokens);
        return new Fingerprint(md5, sh, cleaned);
    }

    /**
     * 自适应汉明距离阈值
     * <p>
     * 短文本(<20字)用2，中等(<100字)用3，长文本(>=100字)用4。
     */
    private int adaptiveThreshold(int textLength) {
        if (textLength < 20) return 2;
        if (textLength < 100) return 3;
        return 4;
    }
}
