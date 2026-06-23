package com.questionrecommend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionrecommend.client.MilvusClientWrapper;
import com.questionrecommend.config.AppProperties;
import com.questionrecommend.model.Question;
import com.questionrecommend.util.TextCleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 题目 CRUD 服务 — 等价 Python ES CRUD 接口
 * <p>
 * 数据同步策略（参考评估文档第 7 章）：
 * <ul>
 *   <li>Milvus: 向量 + 标量字段（id, vector, difficulty, subject, phase, knowledge...）</li>
 *   <li>PostgreSQL: 完整元数据（answer, analysis, options, tagList 等 JSON 字段）</li>
 * </ul>
 * <p>
 * 每个 CRUD 操作同时写 Milvus 和 PostgreSQL。
 */
@Service
public class CrudService {

    private static final Logger log = LoggerFactory.getLogger(CrudService.class);

    private final MilvusClientWrapper milvusClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final VectorService vectorService;

    /** PostgreSQL 仓库（可选，非必须） */
    private final QuestionRepository questionRepository;

    public CrudService(MilvusClientWrapper milvusClient,
                        AppProperties properties,
                        ObjectMapper objectMapper,
                        VectorService vectorService,
                        QuestionRepository questionRepository) {
        this.milvusClient = milvusClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.vectorService = vectorService;
        this.questionRepository = questionRepository;
    }

    /**
     * 创建或更新题目
     * <p>
     * 等价 Python: POST /elasticsearch/create_question
     *
     * @param questionData 题目完整数据（含 vector）
     * @return 操作结果
     */
    public Map<String, Object> createOrUpdate(Map<String, Object> questionData) {
        String questionId = Objects.toString(questionData.get("id"), null);
        if (questionId == null) {
            throw new IllegalArgumentException("题目 ID 不能为空");
        }

        // 1. 如果未提供向量，自动生成
        if (!questionData.containsKey("vector") || questionData.get("vector") == null) {
            List<Float> vector = vectorService.buildVector(questionData);
            questionData.put("vector", vector);
        }

        // 2. 构建 Milvus 记录
        Map<String, Object> milvusRecord = buildMilvusRecord(questionData);

        // 3. 写入 Milvus
        milvusClient.insert(Collections.singletonList(milvusRecord));

        // 4. 写入 PostgreSQL
        Question question = convertToEntity(questionData);
        questionRepository.save(question);

        log.info("题目创建/更新成功: id={}", questionId);
        return Map.of("id", questionId, "status", "created");
    }

    /**
     * 删除题目
     * <p>
     * 等价 Python: POST /elasticsearch/delete_question
     */
    public Map<String, Object> delete(String questionId) {
        // 1. 从 Milvus 删除
        milvusClient.deleteByIds(Collections.singletonList(questionId));

        // 2. 从 PostgreSQL 删除
        questionRepository.deleteById(questionId);

        log.info("题目删除成功: id={}", questionId);
        return Map.of("id", questionId, "status", "deleted");
    }

    /**
     * 根据 ID 查询
     */
    public Map<String, Object> getById(String questionId) {
        // 优先从 PostgreSQL 读取完整数据
        Optional<Question> opt = questionRepository.findById(questionId);
        if (opt.isPresent()) {
            return convertToMap(opt.get());
        }
        // fallback: 从 Milvus 读取
        return milvusClient.queryById(questionId);
    }

    // ====== 内部方法 ======

    /**
     * 构建 Milvus 扁平化记录
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMilvusRecord(Map<String, Object> data) {
        Map<String, Object> record = new LinkedHashMap<>();

        record.put("id", data.get("id"));
        record.put("vector", data.get("vector"));
        record.put("difficulty", data.get("difficulty"));
        record.put("baseTypeId", data.get("baseTypeId"));
        record.put("title", data.get("title"));

        // 扁平化嵌套字段
        Object subject = data.get("subject");
        if (subject instanceof Map<?, ?> map) {
            record.put("subjectTagCode", map.get("tagCode"));
        }
        Object phase = data.get("phase");
        if (phase instanceof Map<?, ?> map) {
            record.put("phaseTagCode", map.get("tagCode"));
        }
        Object type = data.get("type");
        if (type instanceof Map<?, ?> map) {
            record.put("typeTagCode", map.get("tagCode"));
        }

        // tagCodes 列表
        List<String> tagCodes = new ArrayList<>();
        Object tagList = data.get("tagList");
        if (tagList instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> tagMap) {
                    Object code = tagMap.get("tagCode");
                    if (code != null) tagCodes.add(code.toString());
                }
            }
        }
        record.put("tagCodes", tagCodes);

        // 知识点列表
        record.put("mainLlmKnowledge", data.getOrDefault("mainLlmKnowledge", Collections.emptyList()));
        record.put("allLlmKnowledge", data.getOrDefault("allLlmKnowledge", Collections.emptyList()));

        // 章节索引
        record.put("chapterIndex", data.getOrDefault("chapterIndex", -1));

        // 题面 MD5（用于去重）
        String title = Objects.toString(data.get("title"), "");
        record.put("titleMd5", TextCleanUtils.md5Hex(title));

        return record;
    }

    /**
     * 将 Map 数据转换为 Question 实体
     */
    private Question convertToEntity(Map<String, Object> data) {
        Question q = new Question();
        q.setId(Objects.toString(data.get("id"), null));
        q.setDifficulty(data.get("difficulty") instanceof Number n ? n.intValue() : null);
        q.setBaseTypeId(data.get("baseTypeId") instanceof Number n ? n.longValue() : null);
        q.setTitle(Objects.toString(data.get("title"), null));
        q.setTitleHtml(Objects.toString(data.get("titleHtml"), null));
        q.setAnalysis(Objects.toString(data.get("analysis"), null));
        q.setAnalysisHtml(Objects.toString(data.get("analysisHtml"), null));

        try {
            q.setOptionsJson(objectMapper.writeValueAsString(data.get("options")));
            q.setAnswerJson(objectMapper.writeValueAsString(data.get("answer")));
            q.setTagListJson(objectMapper.writeValueAsString(data.get("tagList")));
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
        }

        return q;
    }

    /**
     * 将 Question 实体转换为 Map
     */
    private Map<String, Object> convertToMap(Question q) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", q.getId());
        map.put("difficulty", q.getDifficulty());
        map.put("baseTypeId", q.getBaseTypeId());
        map.put("title", q.getTitle());
        map.put("titleHtml", q.getTitleHtml());
        map.put("analysis", q.getAnalysis());
        map.put("analysisHtml", q.getAnalysisHtml());
        try {
            if (q.getOptionsJson() != null)
                map.put("options", objectMapper.readValue(q.getOptionsJson(), List.class));
            if (q.getAnswerJson() != null)
                map.put("answer", objectMapper.readValue(q.getAnswerJson(), List.class));
            if (q.getTagListJson() != null)
                map.put("tagList", objectMapper.readValue(q.getTagListJson(), List.class));
        } catch (Exception e) {
            log.warn("JSON 反序列化失败: {}", e.getMessage());
        }
        return map;
    }
}
