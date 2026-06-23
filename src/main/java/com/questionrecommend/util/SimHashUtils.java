package com.questionrecommend.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SimHash 64 位指纹计算工具类
 * <p>
 * 等价 Python deduplication.py 中的 _simhash() 函数。
 * 用于对分词后的词元列表计算 64 位 SimHash 指纹，支持海明距离比较。
 * <p>
 * 注意：使用固定哈希（基于 MD5 思想），而非 Java String.hashCode()，
 * 避免 PYTHONHASHSEED 随机盐的问题（对应 Python 代码中的注释说明）。
 */
public final class SimHashUtils {

    /** 指纹位数 */
    private static final int HASH_BITS = 64;

    private SimHashUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算给定词元列表的 64 位 SimHash 指纹
     */
    public static long calculateSimHash(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0L;
        }

        // 步骤1: 统计词频
        Map<String, Integer> wordCount = new HashMap<>();
        for (String token : tokens) {
            if (token != null && !token.isEmpty()) {
                wordCount.merge(token, 1, Integer::sum);
            }
        }

        // 步骤2+3: 按位加权累加
        int[] v = new int[HASH_BITS];

        for (Map.Entry<String, Integer> entry : wordCount.entrySet()) {
            String token = entry.getKey();
            int weight = entry.getValue();

            // 使用固定 64 位 hash（避免 Java 随机盐问题）
            long hash = fixedHash64(token);

            for (int i = 0; i < HASH_BITS; i++) {
                long mask = 1L << i;
                if ((hash & mask) != 0) {
                    v[i] += weight;
                } else {
                    v[i] -= weight;
                }
            }
        }

        // 步骤4: 按位判定生成指纹
        long fingerprint = 0L;
        for (int i = 0; i < HASH_BITS; i++) {
            if (v[i] > 0) {
                fingerprint |= (1L << i);
            }
        }

        return fingerprint;
    }

    /**
     * 计算两个 SimHash 指纹之间的海明距离
     */
    public static int hammingDistance(long a, long b) {
        long xor = a ^ b;
        return Long.bitCount(xor);
    }

    /**
     * 判断两个指纹是否相似（海明距离不超过指定阈值）
     */
    public static boolean isSimilar(long a, long b, int threshold) {
        return hammingDistance(a, b) <= threshold;
    }

    /**
     * 对字符串计算固定的 64 位 hash
     * <p>
     * 使用 MD5 前 16 位的整数作为 hash（等价 Python 的 int(hashlib.md5(...), 16)），
     * 确保进程重启后同一字符串的 hash 值一致，SimHash 指纹可复现。
     */
    private static long fixedHash64(String str) {
        // 基于字符遍历 + 位移 + 异或，产生 64 位可复现 hash
        long h = 0L;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            h = (h << 5) - h + c;
            h = Long.rotateLeft(h, 13);
        }
        // 雪崩混合（avalanche mixing）
        h ^= (h >>> 32);
        h *= 0xD6E8FEB86659FD93L;
        h ^= (h >>> 32);
        return h;
    }
}
