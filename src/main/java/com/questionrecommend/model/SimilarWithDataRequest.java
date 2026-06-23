package com.questionrecommend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 带数据推荐请求体 — 等价 Python similar_with_more_information.py 的 RequestModel
 * <p>
 * 调用方预加载了源题的所有数据（含 vector），由服务端直接使用。
 * 不需要再从 Milvus 读取源题数据。
 */
public class SimilarWithDataRequest {

    /** 源题完整数据（含 vector 字段） */
    @JsonProperty("data")
    private Map<String, Object> sourceData;

    /** 返回题目数量 */
    private int size = 10;

    /** 禁用题目列表 */
    private List<String> disableQuestion;

    @JsonProperty("similarity_weight")
    private double simWeight = 0.5;

    @JsonProperty("tag_weight")
    private double tagWeight = 0.2;

    @JsonProperty("main_klg_weight")
    private double mainKlgWeight = 0.3;

    @JsonProperty("all_klg_weight")
    private double allKlgWeight = 0.2;

    /** 强制匹配难度等级 */
    private Integer difficulty;

    @JsonProperty("type")
    private List<String> questionType;

    @JsonProperty("recommend_mode")
    private String mode = "custom";

    @JsonProperty("mainLlmKnowledge")
    private List<String> mainLlmKnowledge;

    @JsonProperty("allLlmKnowledge")
    private List<String> allLlmKnowledge;

    @JsonProperty("use_cache")
    private boolean useCache = true;

    // ====== Getters and Setters ======

    public Map<String, Object> getSourceData() { return sourceData; }
    public void setSourceData(Map<String, Object> sourceData) { this.sourceData = sourceData; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public List<String> getDisableQuestion() { return disableQuestion; }
    public void setDisableQuestion(List<String> disableQuestion) { this.disableQuestion = disableQuestion; }

    public double getSimWeight() { return simWeight; }
    public void setSimWeight(double simWeight) { this.simWeight = simWeight; }

    public double getTagWeight() { return tagWeight; }
    public void setTagWeight(double tagWeight) { this.tagWeight = tagWeight; }

    public double getMainKlgWeight() { return mainKlgWeight; }
    public void setMainKlgWeight(double mainKlgWeight) { this.mainKlgWeight = mainKlgWeight; }

    public double getAllKlgWeight() { return allKlgWeight; }
    public void setAllKlgWeight(double allKlgWeight) { this.allKlgWeight = allKlgWeight; }

    public Integer getDifficulty() { return difficulty; }
    public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }

    public List<String> getQuestionType() { return questionType; }
    public void setQuestionType(List<String> questionType) { this.questionType = questionType; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public List<String> getMainLlmKnowledge() { return mainLlmKnowledge; }
    public void setMainLlmKnowledge(List<String> mainLlmKnowledge) { this.mainLlmKnowledge = mainLlmKnowledge; }

    public List<String> getAllLlmKnowledge() { return allLlmKnowledge; }
    public void setAllLlmKnowledge(List<String> allLlmKnowledge) { this.allLlmKnowledge = allLlmKnowledge; }

    public boolean isUseCache() { return useCache; }
    public void setUseCache(boolean useCache) { this.useCache = useCache; }
}
