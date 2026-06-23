package com.questionrecommend.model;

import java.util.List;

/**
 * 批量推荐请求体 — 等价 Python similar.py 的 BatchRequestModel
 */
public class BatchRecommendRequest {

    /** 题目 ID 列表（1-100 个） */
    private List<String> ids;

    /** 每个题目推荐数量 */
    private int size = 10;

    /** 全局禁用题目列表 */
    private List<String> disableQuestion;

    /** 向量相似度权重 */
    private double similarityWeight = 0.6;

    /** 标签权重 */
    private double tagWeight = 0.4;

    /** 主要知识点权重 */
    private double mainKlgWeight = 0.0;

    /** 全部知识点权重 */
    private double allKlgWeight = 0.0;

    /** 强制匹配难度等级（0-5） */
    private Integer difficulty;

    /** 题型列表 */
    private List<String> questionType;

    /** 推荐模式 */
    private String recommendMode = "custom";

    /** 是否使用缓存 */
    private boolean useCache = true;

    // ====== Getters and Setters ======

    public List<String> getIds() { return ids; }
    public void setIds(List<String> ids) { this.ids = ids; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public List<String> getDisableQuestion() { return disableQuestion; }
    public void setDisableQuestion(List<String> disableQuestion) { this.disableQuestion = disableQuestion; }

    public double getSimilarityWeight() { return similarityWeight; }
    public void setSimilarityWeight(double similarityWeight) { this.similarityWeight = similarityWeight; }

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

    public String getRecommendMode() { return recommendMode; }
    public void setRecommendMode(String recommendMode) { this.recommendMode = recommendMode; }

    public boolean isUseCache() { return useCache; }
    public void setUseCache(boolean useCache) { this.useCache = useCache; }
}
