package com.questionrecommend.model;

import java.util.List;

/**
 * 候选题（精排内部使用） — 等价 Python SimilarScore + 推荐结果对象
 * <p>
 * 用于 R0/R1 两路查询结果融合后，到精排阶段的中间表示。
 * 精排后写入 Redis 缓存和 API 返回的也是该结构。
 */
public class CandidateResult {

    /** 题目 ID */
    private String id;

    /** 综合得分（含路径奖励，精排后确定） */
    private double score;

    /** 向量相似度（cosine 归一化 [0,1]） */
    private double similarityScore;

    /** 标签 Jaccard [0,1] */
    private double tagMatchScore;

    /** 主知识点 Jaccard [0,1] */
    private double mainKlgScore;

    /** 全部知识点 Jaccard [0,1] */
    private double allKlgScore;

    /** 召回路径：R0_knowledge / R1_vector / R1_supplement */
    private String path;

    /** 原始题目数据（含标量字段） */
    private Question question;

    // ====== Constructors ======

    public CandidateResult() {
    }

    // ====== Getters and Setters ======

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public double getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(double similarityScore) { this.similarityScore = similarityScore; }

    public double getTagMatchScore() { return tagMatchScore; }
    public void setTagMatchScore(double tagMatchScore) { this.tagMatchScore = tagMatchScore; }

    public double getMainKlgScore() { return mainKlgScore; }
    public void setMainKlgScore(double mainKlgScore) { this.mainKlgScore = mainKlgScore; }

    public double getAllKlgScore() { return allKlgScore; }
    public void setAllKlgScore(double allKlgScore) { this.allKlgScore = allKlgScore; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Question getQuestion() { return question; }
    public void setQuestion(Question question) { this.question = question; }

    /**
     * 转换为 API 返回的 Map 格式
     * 等价 Python similar_with_more_information.py 的 result_question 格式
     */
    public java.util.Map<String, Object> toResultMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("_id", id);
        map.put("_path", path);
        map.put("score", Math.round(score * 10000.0) / 10000.0);
        map.put("similarity_score", Math.round(similarityScore * 10000.0) / 10000.0);
        map.put("tag_match_score", Math.round(tagMatchScore * 10000.0) / 10000.0);
        map.put("main_klg_score", Math.round(mainKlgScore * 10000.0) / 10000.0);
        map.put("all_klg_score", Math.round(allKlgScore * 10000.0) / 10000.0);
        if (question != null) {
            map.put("title", question.getTitle());
            map.put("difficulty", question.getDifficulty());
            map.put("type", question.getType());
            map.put("subject", question.getSubject());
            map.put("phase", question.getPhase());
        }
        return map;
    }
}
