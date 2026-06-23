package com.questionrecommend.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * HTML 清洗工具类
 * <p>
 * 等价 Python similar_with_more_information.py 中的 clean_html() 函数。
 * 使用 Jsoup 替代 Python BeautifulSoup 进行 HTML 解析和清洗。
 */
public final class HtmlCleanUtils {

    private HtmlCleanUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 清洗 HTML，提取纯文本
     * <p>
     * 等价 Python: BeautifulSoup(raw, "html.parser").get_text(" ", strip=True)
     *
     * @param raw 原始 HTML 字符串
     * @return 清洗后的纯文本
     */
    public static String cleanHtml(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // 使用 Jsoup 解析 HTML 并提取纯文本，词间用空格分隔
        return Jsoup.parse(raw).text();
    }

    /**
     * 清洗 HTML 并保留安全标签（用于需要保留基本格式的场景）
     *
     * @param raw 原始 HTML 字符串
     * @return 清洗后的安全 HTML
     */
    public static String sanitizeHtml(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // 仅允许安全的标签（b, i, u, em, strong, a, p, br）
        return Jsoup.clean(raw, Safelist.basic());
    }

    /**
     * 从 HTML 中提取文本用于向量化（高信息密度）
     * <p>
     * 等价 Python build_vector_text() 中的处理逻辑，
     * 将题面 + 选项拼接成干净的文本。
     */
    public static String extractTextForVector(String titleHtml, java.util.List<String> options) {
        StringBuilder sb = new StringBuilder();
        String titleText = cleanHtml(titleHtml);
        if (!titleText.isEmpty()) {
            sb.append(titleText);
        }
        if (options != null && !options.isEmpty()) {
            for (String opt : options) {
                String cleanOpt = cleanHtml(opt);
                if (!cleanOpt.isEmpty()) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(cleanOpt);
                }
            }
        }
        return sb.toString();
    }
}
