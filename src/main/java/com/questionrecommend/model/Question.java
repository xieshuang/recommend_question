package com.questionrecommend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.util.List;
import java.util.Map;

/**
 * 题目实体模型 — 完整数据
 * <p>
 * 对应 Milvus 扁平化 Schema 中的所有字段 + PostgreSQL 中的完整元数据。
 * <p>
 * 数据同步策略：
 * <ul>
 *   <li>Milvus: 向量 + 过滤字段（id, vector, difficulty, baseTypeId, tagCodes, knowledge）</li>
 *   <li>PostgreSQL: 完整字段（含 answer, analysis, options, tagList 等 JSON 嵌套数据）</li>
 * </ul>
 */
@Entity
@Table(name = "questions")
public class Question {

    @Id
    @Column(length = 64)
    private String id;

    /** 题面向量（768维）— 仅用于 API 传入，不持久化到 PostgreSQL */
    @Transient
    private List<Float> vector;

    /** 难度等级（1-5） */
    private Integer difficulty;

    /** 基础题型 ID */
    private Long baseTypeId;

    /** 学科标签编码（Milvus 标量字段） */
    @Column(length = 32)
    private String subjectTagCode;

    /** 学段标签编码 */
    @Column(length = 32)
    private String phaseTagCode;

    /** 题型标签编码 */
    @Column(length = 32)
    private String typeTagCode;

    /** 聚合标签编码列表（JSON 序列化） */
    @Column(columnDefinition = "TEXT")
    private String tagCodesJson;

    /** 主知识点列表（JSON 序列化） */
    @Column(columnDefinition = "TEXT")
    private String mainLlmKnowledgeJson;

    /** 全部知识点列表（JSON 序列化） */
    @Column(columnDefinition = "TEXT")
    private String allLlmKnowledgeJson;

    /** 章节排序索引 */
    private Long chapterIndex;

    /** 题面 MD5（用于去重） */
    @Column(length = 64)
    private String titleMd5;

    /** 题面文本 */
    @Column(length = 4096)
    private String title;

    /** 题面 HTML */
    @Column(columnDefinition = "TEXT")
    private String titleHtml;

    /** 选项列表（JSON） */
    @Column(columnDefinition = "TEXT")
    private String optionsJson;

    /** 答案列表（JSON） */
    @Column(columnDefinition = "TEXT")
    private String answerJson;

    /** 题目解析 */
    @Column(columnDefinition = "TEXT")
    private String analysis;

    /** 解析 HTML */
    @Column(columnDefinition = "TEXT")
    private String analysisHtml;

    /** 标签列表（JSON） */
    @Column(columnDefinition = "TEXT")
    private String tagListJson;

    // ====== 学科/学段/题型完整对象（JSON 反序列化用） ======
    @Transient
    private Map<String, Object> subject;
    @Transient
    private Map<String, Object> phase;
    @Transient
    private Map<String, Object> type;
    @Transient
    private List<Map<String, Object>> tagList;

    // ====== Getters and Setters ======

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public List<Float> getVector() { return vector; }
    public void setVector(List<Float> vector) { this.vector = vector; }

    public Integer getDifficulty() { return difficulty; }
    public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }

    public Long getBaseTypeId() { return baseTypeId; }
    public void setBaseTypeId(Long baseTypeId) { this.baseTypeId = baseTypeId; }

    public String getSubjectTagCode() { return subjectTagCode; }
    public void setSubjectTagCode(String subjectTagCode) { this.subjectTagCode = subjectTagCode; }

    public String getPhaseTagCode() { return phaseTagCode; }
    public void setPhaseTagCode(String phaseTagCode) { this.phaseTagCode = phaseTagCode; }

    public String getTypeTagCode() { return typeTagCode; }
    public void setTypeTagCode(String typeTagCode) { this.typeTagCode = typeTagCode; }

    public String getTagCodesJson() { return tagCodesJson; }
    public void setTagCodesJson(String tagCodesJson) { this.tagCodesJson = tagCodesJson; }

    public String getMainLlmKnowledgeJson() { return mainLlmKnowledgeJson; }
    public void setMainLlmKnowledgeJson(String mainLlmKnowledgeJson) { this.mainLlmKnowledgeJson = mainLlmKnowledgeJson; }

    public String getAllLlmKnowledgeJson() { return allLlmKnowledgeJson; }
    public void setAllLlmKnowledgeJson(String allLlmKnowledgeJson) { this.allLlmKnowledgeJson = allLlmKnowledgeJson; }

    public Long getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(Long chapterIndex) { this.chapterIndex = chapterIndex; }

    public String getTitleMd5() { return titleMd5; }
    public void setTitleMd5(String titleMd5) { this.titleMd5 = titleMd5; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTitleHtml() { return titleHtml; }
    public void setTitleHtml(String titleHtml) { this.titleHtml = titleHtml; }

    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }

    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }

    public String getAnalysis() { return analysis; }
    public void setAnalysis(String analysis) { this.analysis = analysis; }

    public String getAnalysisHtml() { return analysisHtml; }
    public void setAnalysisHtml(String analysisHtml) { this.analysisHtml = analysisHtml; }

    public String getTagListJson() { return tagListJson; }
    public void setTagListJson(String tagListJson) { this.tagListJson = tagListJson; }

    public Map<String, Object> getSubject() { return subject; }
    public void setSubject(Map<String, Object> subject) { this.subject = subject; }

    public Map<String, Object> getPhase() { return phase; }
    public void setPhase(Map<String, Object> phase) { this.phase = phase; }

    public Map<String, Object> getType() { return type; }
    public void setType(Map<String, Object> type) { this.type = type; }

    public List<Map<String, Object>> getTagList() { return tagList; }
    public void setTagList(List<Map<String, Object>> tagList) { this.tagList = tagList; }
}
