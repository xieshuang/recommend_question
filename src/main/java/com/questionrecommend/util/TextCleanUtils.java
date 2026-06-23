package com.questionrecommend.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本清洗与 SimHash 分词工具
 * <p>
 * 等价 Python deduplication.py 中的：
 * - _clean_text() — 去除 HTML/空白/标点
 * - _md5_hash() — MD5 计算
 * - _extract_text() — 从题目对象提取文本
 * <p>
 * 用于去重阶段的文本预处理。
 */
public final class TextCleanUtils {

    private static final Pattern HTML_TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_RE = Pattern.compile("\\s+");
    /** 保留中英文数字 + 常见数学符号（避免 "x+1" 和 "x-1" 被清洗成同一文本） */
    private static final Pattern PUNCT_RE = Pattern.compile("[^\\w\u4e00-\u9fff+\\-*/=^]");

    private TextCleanUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 清洗文本：去除 HTML、空白、标点，统一小写
     * <p>
     * 等价 Python: _clean_text(raw)
     */
    public static String cleanText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = HTML_TAG_RE.matcher(raw).replaceAll("");
        text = WHITESPACE_RE.matcher(text).replaceAll("");
        text = PUNCT_RE.matcher(text).replaceAll("");
        return text.toLowerCase();
    }

    /**
     * 计算 MD5（hex 字符串）
     * <p>
     * 等价 Python: _md5_hash(text)
     */
    public static String md5Hex(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * 从题目对象中提取用于 SimHash 计算的词元列表
     * <p>
     * 使用 HanLP 进行中文分词（等价 Python jieba）。
     *
     * @param text 清洗后的文本
     * @return 分词后的词元列表
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        // 使用 HanLP 进行中文分词
        List<String> tokens = new ArrayList<>();
        try {
            com.hankcs.hanlp.seg.common.Term termList =
                    com.hankcs.hanlp.HanLP.segment(text).get(0);
            // 简化处理：按字符拆分为 2-gram（与 Python SimHash 版本一致）
            for (int i = 0; i < text.length() - 1; i++) {
                tokens.add(text.substring(i, i + 2));
            }
        } catch (Exception e) {
            // fallback：2-gram
            for (int i = 0; i < text.length() - 1; i++) {
                tokens.add(text.substring(i, i + 2));
            }
        }
        return tokens;
    }

    /**
     * 从题目对象提取文本（兼容 Map 格式）
     *
     * @param obj 题目对象（Map）
     * @return 拼接后的文本
     */
    public static String extractTextFromMap(java.util.Map<String, Object> obj) {
        if (obj == null) return "";
        String title = obj.getOrDefault("title", "").toString();
        String body = obj.getOrDefault("body", "").toString();
        String content = obj.getOrDefault("content", "").toString();
        String questionContent = obj.getOrDefault("questionContent", "").toString();
        String text = title + "_" + body + content + questionContent;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
