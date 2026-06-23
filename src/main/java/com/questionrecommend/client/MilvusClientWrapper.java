package com.questionrecommend.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionrecommend.config.AppProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.QueryResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 客户端封装 — 替代原有的 ES 客户端
 * <p>
 * 提供与 Python es_client.py 等价的方法签名，包括：
 * <ul>
 *   <li>{@link #search} — ANN 向量检索（等价 ES KNN）</li>
 *   <li>{@link #queryById} — 根据 ID 获取单条（等价 ES get）</li>
 *   <li>{@link #queryByIds} — 批量获取（等价 ES mget）</li>
 *   <li>{@link #insert} — 写入数据（等价 ES index）</li>
 *   <li>{@link #deleteByIds} — 删除数据（等价 ES delete）</li>
 * </ul>
 */
@Component
public class MilvusClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(MilvusClientWrapper.class);

    private final MilvusServiceClient milvusClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    /** Milvus 集合中的输出字段列表（对应扁平化 Schema） */
    private static final List<String> DEFAULT_OUT_FIELDS = Arrays.asList(
            "id", "difficulty", "baseTypeId",
            "subjectTagCode", "phaseTagCode", "typeTagCode",
            "tagCodes", "mainLlmKnowledge", "allLlmKnowledge",
            "chapterIndex", "titleMd5", "title"
    );

    public MilvusClientWrapper(MilvusServiceClient milvusClient,
                                AppProperties properties,
                                ObjectMapper objectMapper) {
        this.milvusClient = milvusClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * ANN 向量检索 — 等价 ES KNN 查询
     * <p>
     * 该方法是 R0/R1 两路召回的核心，Milvus 返回按 cosine 距离排序的候选列表，
     * 后续由 Java 应用层进行 Jaccard 计算和加权精排。
     *
     * @param topK   召回数量（等价 ES knn.k）
     * @param expr   过滤表达式（等价 ES bool query filter）
     * @param vector 查询向量（768 维 float 列表）
     * @return 搜索结果列表（Map 格式，包含所有标量字段）
     */
    public List<Map<String, Object>> search(int topK, String expr, List<Float> vector) {
        AppProperties.Milvus cfg = properties.getMilvus();

        String searchParams = "HNSW".equalsIgnoreCase(cfg.getIndexType())
                ? String.format("{\"ef\": %d}", cfg.getSearchEf())
                : String.format("{\"nprobe\": %d}", cfg.getSearchNprobe());

        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(cfg.getCollection())
                .withVectorFieldName(cfg.getVectorField())
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .withFloatVectors(Collections.singletonList(vector))
                .withOutFields(DEFAULT_OUT_FIELDS)
                .withParams(searchParams)
                .withExpr(expr != null && !expr.isEmpty() ? expr : null)
                .build();

        R<SearchResults> response = milvusClient.search(param);

        if (response.getException() != null) {
            log.warn("Milvus 搜索失败: {}", response.getException().getMessage());
            return Collections.emptyList();
        }
        if (response.getData() == null) return Collections.emptyList();

        // 从 grpc SearchResults 构建 SearchResultsWrapper
        SearchResultData resultData = response.getData().getResults();
        if (resultData == null) return Collections.emptyList();

        SearchResultsWrapper wrapper = new SearchResultsWrapper(resultData);
        return parseSearchResults(wrapper, topK);
    }

    /**
     * 解析 SearchResultsWrapper 为统一的 Map 列表
     */
    private List<Map<String, Object>> parseSearchResults(SearchResultsWrapper wrapper, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();

        // 读取 IDScore（含 distance）
        List<SearchResultsWrapper.IDScore> idScores;
        try {
            idScores = wrapper.getIDScore(0);
        } catch (Exception e) {
            log.warn("解析 IDScore 失败: {}", e.getMessage());
            return results;
        }

        if (idScores == null || idScores.isEmpty()) return results;

        // 读取行记录数据
        List<QueryResultsWrapper.RowRecord> records;
        try {
            records = wrapper.getRowRecords();
        } catch (Exception e) {
            log.warn("解析 RowRecords 失败: {}", e.getMessage());
            records = Collections.emptyList();
        }

        for (int i = 0; i < idScores.size() && i < topK; i++) {
            SearchResultsWrapper.IDScore idScore = idScores.get(i);
            Map<String, Object> item = new LinkedHashMap<>();

            // ID
            String id = idScore.getStrID() != null ? idScore.getStrID() : String.valueOf(idScore.getLongID());
            item.put("_id", id);

            // Cosine distance → similarity
            // Milvus COSINE: score = 1 - cosine_similarity, range [0, 2]
            // 0 = most similar, 2 = least similar
            double distance = idScore.getScore();
            double cosineSimilarity = 1.0 - distance; // [1, -1]
            double normalizedScore = (cosineSimilarity + 1.0) / 2.0; // [0, 1]

            item.put("_score", cosineSimilarity);
            item.put("_normalizedScore", Math.max(0.0, Math.min(1.0, normalizedScore)));

            // 填充标量字段
            if (i < records.size()) {
                QueryResultsWrapper.RowRecord record = records.get(i);
                for (String field : DEFAULT_OUT_FIELDS) {
                    Object val = record.get(field);
                    if (val != null) {
                        item.put(field, val);
                    }
                }
            }

            results.add(item);
        }

        return results;
    }

    /**
     * 根据 ID 查询单条题目 — 等价 ES get
     */
    public Map<String, Object> queryById(String id) {
        String collectionName = properties.getMilvus().getCollection();

        QueryParam param = QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(String.format("id == \"%s\"", id))
                .withOutFields(DEFAULT_OUT_FIELDS)
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build();

        R<QueryResults> response = milvusClient.query(param);

        if (response.getException() != null) {
            log.warn("Milvus 查询失败 id={}: {}", id, response.getException().getMessage());
            return null;
        }
        if (response.getData() == null) return null;

        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();
        if (records == null || records.isEmpty()) return null;

        return convertRowRecordToMap(records.get(0));
    }

    /**
     * 批量查询 — 等价 ES mget
     */
    public Map<String, Map<String, Object>> queryByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();

        String collectionName = properties.getMilvus().getCollection();
        String idExpr = ids.stream()
                .map(id -> String.format("\"%s\"", id))
                .collect(Collectors.joining(", "));

        QueryParam param = QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(String.format("id in [%s]", idExpr))
                .withOutFields(DEFAULT_OUT_FIELDS)
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build();

        R<QueryResults> response = milvusClient.query(param);

        if (response.getException() != null) {
            log.warn("Milvus 批量查询失败: {}", response.getException().getMessage());
            return Collections.emptyMap();
        }
        if (response.getData() == null) return Collections.emptyMap();

        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();
        if (records == null) return Collections.emptyMap();

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (QueryResultsWrapper.RowRecord record : records) {
            Map<String, Object> row = convertRowRecordToMap(record);
            Object recordId = record.get("id");
            if (recordId != null) {
                result.put(recordId.toString(), row);
            }
        }
        return result;
    }

    /**
     * 插入数据 — 等价 ES index
     * <p>
     * 使用 InsertParam.Field 方式批量插入。
     * 每个字段作为独立的列表传入。
     */
    public void insert(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;

        String collectionName = properties.getMilvus().getCollection();

        // 按列组织数据
        Map<String, List<Object>> columnData = new LinkedHashMap<>();
        // 添加 vector 字段
        columnData.put("vector", new ArrayList<>());
        // 添加所有标量字段
        for (String field : DEFAULT_OUT_FIELDS) {
            columnData.put(field, new ArrayList<>());
        }

        for (Map<String, Object> record : records) {
            for (String field : columnData.keySet()) {
                columnData.get(field).add(record.get(field));
            }
        }

        // 构建 InsertParam.Field 列表
        List<InsertParam.Field> fields = new ArrayList<>();
        for (Map.Entry<String, List<Object>> entry : columnData.entrySet()) {
            fields.add(new InsertParam.Field(entry.getKey(), entry.getValue()));
        }

        InsertParam param = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.insert(param);
        if (response.getException() != null) {
            log.warn("Milvus 插入失败: {}", response.getException().getMessage());
        } else {
            log.debug("Milvus 插入成功: {} 条", records.size());
        }
    }

    /**
     * 删除数据 — 等价 ES delete
     */
    public void deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;

        String collectionName = properties.getMilvus().getCollection();
        String idExpr = ids.stream()
                .map(id -> String.format("\"%s\"", id))
                .collect(Collectors.joining(", "));

        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(String.format("id in [%s]", idExpr))
                .build();

        R<MutationResult> response = milvusClient.delete(param);
        if (response.getException() != null) {
            log.warn("Milvus 删除失败: {}", response.getException().getMessage());
        } else {
            log.debug("Milvus 删除成功: {} 条", ids.size());
        }
    }

    // ====== 内部辅助方法 ======

    /**
     * 将 QueryResultsWrapper.RowRecord 转换为 Map
     */
    private Map<String, Object> convertRowRecordToMap(QueryResultsWrapper.RowRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (record == null) return map;

        for (String fieldName : DEFAULT_OUT_FIELDS) {
            Object value = record.get(fieldName);
            if (value != null) {
                map.put(fieldName, value);
            }
        }
        // 注入 _id
        Object id = record.get("id");
        if (id != null) {
            map.put("_id", id.toString());
        }
        return map;
    }
}
