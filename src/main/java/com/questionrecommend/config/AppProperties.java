package com.questionrecommend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局业务配置 — 等价 Python app/__init__.py + .env
 * <p>
 * 所有环境变量通过 @ConfigurationProperties 自动绑定到 Java Bean。
 * 支持 application.yml 配置、环境变量覆盖（Spring Boot 标准机制）。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Milvus 相关配置 */
    private Milvus milvus = new Milvus();

    /** Redis 缓存配置 */
    private Cache cache = new Cache();

    /** 向量服务配置（通义千问 text_to_vector） */
    private Vector vector = new Vector();

    /** 并发控制配置 */
    private Concurrency concurrency = new Concurrency();

    /** 日志配置 */
    private Log log = new Log();

    /** 章节标签文件路径 */
    private String tagDataPath = "classpath:tagType10_list.json";

    // ====== 内部类 ======

    /**
     * Milvus 配置 — 等价 ES 配置的替代品
     * <p>
     * Milvus 负责向量 ANN 检索 + 标量过滤，
     * 混合评分的职责从 Painless 脚本迁移到 Java 应用层。
     */
    public static class Milvus {
        /** Milvus 服务地址（gRPC），如 localhost:19530 */
        private String host = "localhost:19530";
        /** 连接超时（毫秒） */
        private long connectTimeoutMs = 10000;
        /** RPC 调用超时（毫秒） */
        private long rpcTimeoutMs = 30000;
        /** 集合名称，如 question_vectors */
        private String collection = "question_vectors";
        /** 向量字段名 */
        private String vectorField = "vector";
        /** 向量维度（通义千问 text_to_vector 输出维度） */
        private int vectorDimension = 768;
        /** 索引类型：HNSW / IVF_FLAT / IVF_SQ8 */
        private String indexType = "HNSW";
        /** HNSW 参数 M */
        private int hnswM = 16;
        /** HNSW 参数 efConstruction */
        private int hnswEfConstruction = 200;
        /** 搜索参数 ef */
        private int searchEf = 200;
        /** 搜索参数 nprobe（IVF 时使用） */
        private int searchNprobe = 16;
        /** 是否在启动时自动创建集合和索引 */
        private boolean autoInit = true;

        // getters/setters
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getRpcTimeoutMs() { return rpcTimeoutMs; }
        public void setRpcTimeoutMs(long rpcTimeoutMs) { this.rpcTimeoutMs = rpcTimeoutMs; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public String getVectorField() { return vectorField; }
        public void setVectorField(String vectorField) { this.vectorField = vectorField; }
        public int getVectorDimension() { return vectorDimension; }
        public void setVectorDimension(int vectorDimension) { this.vectorDimension = vectorDimension; }
        public String getIndexType() { return indexType; }
        public void setIndexType(String indexType) { this.indexType = indexType; }
        public int getHnswM() { return hnswM; }
        public void setHnswM(int hnswM) { this.hnswM = hnswM; }
        public int getHnswEfConstruction() { return hnswEfConstruction; }
        public void setHnswEfConstruction(int hnswEfConstruction) { this.hnswEfConstruction = hnswEfConstruction; }
        public int getSearchEf() { return searchEf; }
        public void setSearchEf(int searchEf) { this.searchEf = searchEf; }
        public int getSearchNprobe() { return searchNprobe; }
        public void setSearchNprobe(int searchNprobe) { this.searchNprobe = searchNprobe; }
        public boolean isAutoInit() { return autoInit; }
        public void setAutoInit(boolean autoInit) { this.autoInit = autoInit; }
    }

    /**
     * Redis 缓存配置
     */
    public static class Cache {
        /** Redis 缓存开关 */
        private boolean enabled = false;
        /** 缓存 TTL（秒），默认 5 分钟 */
        private int ttlSeconds = 300;
        /** Redis 主机 */
        private String host = "127.0.0.1";
        /** Redis 端口 */
        private int port = 6379;
        /** Redis 密码 */
        private String password = "";
        /** Redis 数据库编号 */
        private int db = 0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDb() { return db; }
        public void setDb(int db) { this.db = db; }
    }

    /**
     * 通义千问向量服务配置
     */
    public static class Vector {
        /** 向量服务 API 地址 */
        private String url = "http://127.0.0.1:8111/text_to_vector";
        /** API 认证 Token */
        private String auth = "";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getAuth() { return auth; }
        public void setAuth(String auth) { this.auth = auth; }
    }

    /**
     * 并发控制配置 — 等价 Python 信号量配置
     */
    public static class Concurrency {
        /** Milvus 并发信号量（等价 _es_semaphore = 10） */
        private int milvusSemaphore = 10;
        /** 批量接口并发信号量 */
        private int batchMilvusSemaphore = 10;
        /** 批量接口 chunk 间并发上限（等价 _batch_chunk_semaphore = 3） */
        private int batchChunkSemaphore = 3;
        /** 每组处理题目数 */
        private int batchChunkSize = 25;

        public int getMilvusSemaphore() { return milvusSemaphore; }
        public void setMilvusSemaphore(int milvusSemaphore) { this.milvusSemaphore = milvusSemaphore; }
        public int getBatchMilvusSemaphore() { return batchMilvusSemaphore; }
        public void setBatchMilvusSemaphore(int batchMilvusSemaphore) { this.batchMilvusSemaphore = batchMilvusSemaphore; }
        public int getBatchChunkSemaphore() { return batchChunkSemaphore; }
        public void setBatchChunkSemaphore(int batchChunkSemaphore) { this.batchChunkSemaphore = batchChunkSemaphore; }
        public int getBatchChunkSize() { return batchChunkSize; }
        public void setBatchChunkSize(int batchChunkSize) { this.batchChunkSize = batchChunkSize; }
    }

    /**
     * 日志配置
     */
    public static class Log {
        /** 推荐详细日志开关（等价 VERBOSE_RECOMMEND_LOG） */
        private boolean verboseRecommend = false;

        public boolean isVerboseRecommend() { return verboseRecommend; }
        public void setVerboseRecommend(boolean verboseRecommend) { this.verboseRecommend = verboseRecommend; }
    }

    // ====== 父级 getters/setters ======
    public Milvus getMilvus() { return milvus; }
    public void setMilvus(Milvus milvus) { this.milvus = milvus; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
    public Vector getVector() { return vector; }
    public void setVector(Vector vector) { this.vector = vector; }
    public Concurrency getConcurrency() { return concurrency; }
    public void setConcurrency(Concurrency concurrency) { this.concurrency = concurrency; }
    public Log getLog() { return log; }
    public void setLog(Log log) { this.log = log; }
    public String getTagDataPath() { return tagDataPath; }
    public void setTagDataPath(String tagDataPath) { this.tagDataPath = tagDataPath; }
}
