package com.questionrecommend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局状态管理器 — 等价 Python models/global_vars.py
 * <p>
 * 持有启动时加载的章节标签数据，供超纲过滤使用。
 * 线程安全（使用 ConcurrentHashMap 和 volatile）。
 */
@Component
public class GlobalVars {

    private static final Logger log = LoggerFactory.getLogger(GlobalVars.class);

    /** 章节标签排序列表（等价 tagType10_list.json 内容） */
    private volatile List<Map<String, Object>> tagData = Collections.emptyList();

    /** tagCode → 排序索引 的映射（等价 Python tagCode_map） */
    private final Map<String, Integer> tagCodeMap = new ConcurrentHashMap<>();

    /**
     * 设置章节数据
     */
    public void setTagData(List<Map<String, Object>> data) {
        this.tagData = data;
        // 重新构建索引映射
        tagCodeMap.clear();
        for (int i = 0; i < data.size(); i++) {
            Object tagCode = data.get(i).get("tagCode");
            if (tagCode != null) {
                tagCodeMap.put(tagCode.toString(), i);
            }
        }
        log.info("章节数据加载成功, 共 {} 条标签", data.size());
    }

    /**
     * 获取章节数据
     */
    public List<Map<String, Object>> getTagData() {
        return tagData;
    }

    /**
     * 获取章节索引映射
     */
    public Map<String, Integer> getTagCodeMap() {
        return tagCodeMap;
    }

    /**
     * 根据 tagCode 获取排序索引
     *
     * @return 索引值，未找到返回 -1
     */
    public int getTagIndex(String tagCode) {
        return tagCodeMap.getOrDefault(tagCode, -1);
    }
}
