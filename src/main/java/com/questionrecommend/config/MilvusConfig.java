package com.questionrecommend.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Milvus 客户端配置 — 完全替代 ES 客户端
 * <p>
 * 架构变更说明：
 * <ul>
 *   <li>ES KNN → Milvus ANN: {@link MetricType#COSINE} 语义等价</li>
 *   <li>ES Painless script_score → Java 应用层: RankingService 中计算 Jaccard + 加权分</li>
 *   <li>ES nested → Milvus 扁平化 Schema: 学科/学段/题型改用单字段 + Array 类型</li>
 *   <li>ES bool 过滤 → Milvus expr 表达式: 功能等价，语法不同</li>
 * </ul>
 */
@Configuration
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    private final AppProperties properties;

    public MilvusConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public MilvusServiceClient milvusClient() {
        AppProperties.Milvus cfg = properties.getMilvus();
        String[] hostParts = cfg.getHost().split(":");

        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(hostParts[0])
                .withPort(Integer.parseInt(hostParts[1]))
                .withConnectTimeout(cfg.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .withKeepAliveTimeout(10000L, TimeUnit.MILLISECONDS)
                .build();

        MilvusServiceClient client = new MilvusServiceClient(connectParam);
        log.info("Milvus 客户端创建成功: host={}", cfg.getHost());

        if (cfg.isAutoInit()) {
            try {
                initCollectionAndIndex(client);
            } catch (Exception e) {
                log.error("Milvus 自动初始化失败: {}", e.getMessage());
            }
        }

        return client;
    }

    private void initCollectionAndIndex(MilvusServiceClient client) {
        AppProperties.Milvus cfg = properties.getMilvus();
        String collectionName = cfg.getCollection();

        // 检查集合是否已存在
        R<Boolean> hasCollection = client.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());

        if (hasCollection.getData() != null && hasCollection.getData()) {
            log.info("Milvus 集合已存在: {}", collectionName);
            client.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            return;
        }

        // 创建集合 Schema（使用 addFieldType 而非 withFieldType）
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .withPrimaryKey(true)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("vector")
                        .withDataType(DataType.FloatVector)
                        .withDimension(cfg.getVectorDimension())
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("difficulty")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("baseTypeId")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("subjectTagCode")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("phaseTagCode")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("typeTagCode")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("tagCodes")
                        .withDataType(DataType.Array)
                        .withElementType(DataType.VarChar)
                        .withMaxLength(32)
                        .withMaxCapacity(64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("mainLlmKnowledge")
                        .withDataType(DataType.Array)
                        .withElementType(DataType.VarChar)
                        .withMaxLength(128)
                        .withMaxCapacity(32)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("allLlmKnowledge")
                        .withDataType(DataType.Array)
                        .withElementType(DataType.VarChar)
                        .withMaxLength(128)
                        .withMaxCapacity(64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chapterIndex")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("titleMd5")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("title")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(4096)
                        .build())
                .build();

        R<RpcStatus> createResp = client.createCollection(
                CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withSchema(schema)
                        .build());

        if (createResp.getException() != null) {
            log.error("Milvus 集合创建失败: {}", createResp.getException().getMessage());
            return;
        }
        log.info("Milvus 集合创建成功: {}", collectionName);

        // 创建向量索引
        IndexType indexType;
        switch (cfg.getIndexType().toUpperCase()) {
            case "IVF_FLAT": indexType = IndexType.IVF_FLAT; break;
            case "IVF_SQ8":  indexType = IndexType.IVF_SQ8; break;
            default:         indexType = IndexType.HNSW; break;
        }

        String indexParams = indexType == IndexType.HNSW
                ? String.format("{\"M\": %d, \"efConstruction\": %d}", cfg.getHnswM(), cfg.getHnswEfConstruction())
                : "{\"nlist\": 256}";

        R<RpcStatus> indexResp = client.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("vector")
                        .withIndexType(indexType)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam(indexParams)
                        .build());

        if (indexResp.getException() != null) {
            log.warn("Milvus 向量索引创建警告: {}", indexResp.getException().getMessage());
        } else {
            log.info("Milvus 向量索引创建成功: type={}, params={}", cfg.getIndexType(), indexParams);
        }

        // 创建标量字段索引
        createScalarIndex(client, collectionName, "subjectTagCode");
        createScalarIndex(client, collectionName, "phaseTagCode");
        createScalarIndex(client, collectionName, "typeTagCode");
        createScalarIndex(client, collectionName, "baseTypeId");
        createScalarIndex(client, collectionName, "difficulty");

        // 加载集合到内存
        client.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());
        log.info("Milvus 集合已加载到内存: {}", collectionName);
    }

    private void createScalarIndex(MilvusServiceClient client, String collectionName, String fieldName) {
        try {
            R<RpcStatus> resp = client.createIndex(
                    CreateIndexParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withFieldName(fieldName)
                            .withIndexType(IndexType.STL_SORT)
                            .build());
            if (resp.getException() != null) {
                log.warn("标量索引创建警告 [{}]: {}", fieldName, resp.getException().getMessage());
            }
        } catch (Exception e) {
            log.warn("标量索引创建失败 [{}]: {}", fieldName, e.getMessage());
        }
    }

    public SearchParam.Builder buildSearchParam(int topK, String expr,
                                                  List<String> outFields,
                                                  List<List<Float>> queryVectors) {
        AppProperties.Milvus cfg = properties.getMilvus();

        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(cfg.getCollection())
                .withVectorFieldName(cfg.getVectorField())
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .withFloatVectors(queryVectors)
                .withOutFields(outFields);

        String searchParams = "HNSW".equalsIgnoreCase(cfg.getIndexType())
                ? String.format("{\"ef\": %d}", cfg.getSearchEf())
                : String.format("{\"nprobe\": %d}", cfg.getSearchNprobe());

        builder.withParams(searchParams);

        if (expr != null && !expr.isEmpty()) {
            builder.withExpr(expr);
        }

        return builder;
    }
}
