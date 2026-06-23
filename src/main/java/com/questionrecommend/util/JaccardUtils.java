package com.questionrecommend.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Jaccard 相似度计算工具类
 * <p>
 * 等价 Python similar.py 中的 calculate_jaccard_similarity_optimized() 函数。
 * 用于计算两个字符串集合之间的 Jaccard 相似度系数。
 * <p>
 * Jaccard(A, B) = |A ∩ B| / |A ∪ B|, 值域 [0.0, 1.0]
 */
public final class JaccardUtils {

    private JaccardUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算两个集合的 Jaccard 相似度
     * <p>
     * 两个集合都为空时返回 0.0（等价 Python 逻辑）。
     */
    public static double calculateJaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) intersection.size() / union.size();
    }

    /**
     * 快速计算两个集合的 Jaccard 相似度（优化版）
     * <p>
     * 通过 |A ∪ B| = |A| + |B| - |A ∩ B| 公式，避免创建临时集合。
     * 遍历较小的集合以提升性能。
     */
    public static double calculateJaccardOptimized(Set<String> a, Set<String> b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }

        // 遍历较小集合计算交集
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;

        int intersection = 0;
        for (String item : smaller) {
            if (larger.contains(item)) {
                intersection++;
            }
        }

        int union = a.size() + b.size() - intersection;
        if (union <= 0) {
            return 0.0;
        }
        return (double) intersection / union;
    }
}
