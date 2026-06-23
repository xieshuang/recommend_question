package com.questionrecommend.service;

import com.questionrecommend.client.QwenVectorClient;
import com.questionrecommend.util.HtmlCleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向量服务 — 文本转向量
 * <p>
 * 等价 Python similar_with_more_information.py 中的向量化逻辑。
 * 将题面 + 选项拼接成高信息密度文本，调用通义千问向量服务。
 */
@Service
public class VectorService {

    private static final Logger log = LoggerFactory.getLogger(VectorService.class);

    private final QwenVectorClient qwenClient;

    public VectorService(QwenVectorClient qwenClient) {
        this.qwenClient = qwenClient;
    }

    /**
     * 构建向量化文本并调用服务
     * <p>
     * 等价 Python build_vector_text() + embedding()
     *
     * @param sourceData 源题数据（含 title, options 等字段）
     * @return 768 维 float 向量
     */
    @SuppressWarnings("unchecked")
    public List<Float> buildVector(Map<String, Object> sourceData) {
        String titleText = HtmlCleanUtils.cleanHtml(Objects.toString(sourceData.get("title"), ""));
        String titleHtml = Objects.toString(sourceData.get("titleHtml"), "");

        List<String> options = null;
        Object optionsObj = sourceData.get("options");
        if (optionsObj instanceof List<?> list) {
            options = (List<String>) list;
        }

        // 使用 HtmlCleanUtils 构建高信息密度文本
        String vectorText;
        if (titleText.isEmpty()) {
            vectorText = HtmlCleanUtils.extractTextForVector(titleHtml, options);
        } else {
            StringBuilder sb = new StringBuilder(titleText);
            if (options != null) {
                for (String opt : options) {
                    String cleanOpt = HtmlCleanUtils.cleanHtml(opt);
                    if (!cleanOpt.isEmpty()) {
                        sb.append(" ").append(cleanOpt);
                    }
                }
            }
            vectorText = sb.toString();
        }

        if (vectorText.isEmpty()) {
            log.warn("向量化文本为空，使用默认文本");
            vectorText = sourceData.toString();
        }

        log.debug("向量化文本长度: {} 字符", vectorText.length());
        return qwenClient.textToVector(vectorText);
    }
}
