# 题目相似推荐引擎 (Java + Milvus 改造版)

基于 **Milvus 向量数据库** + **Spring Boot 3.3** 的相似题目推荐服务。  
从原 Python + ES 架构改造而来，核心变化：**评分位置从 ES Painless 脚本迁移到 Java 应用层**。

## 架构变化

```
改造前 (Python + ES):               改造后 (Java 21 + Milvus):
┌──────────────────┐               ┌──────────────────────┐
│   FastAPI        │               │   Spring Boot        │
│   (Python)       │               │   (Java 17)          │
└────────┬─────────┘               └──────────┬───────────┘
         │                                     │
┌────────▼────────┐                 ┌─────────▼─────────┐
│ Elasticsearch   │                 │     Milvus         │
│ ─────────────── │                 │ ─────────────────  │
│ • 向量索引       │                 │ • 向量 ANN 检索     │
│ • KNN 检索       │                 │ • 标量过滤 (expr)   │
│ • Painless评分   │                 │ • 扁平化 Schema     │
│ • Nested 查询    │                 └─────────┬─────────┘
│ • 全文数据存储    │                           │
└─────────────────┘                 ┌─────────▼─────────┐
                                    │  PostgreSQL        │
                                    │  (元数据, 可选)     │
                                    └───────────────────┘
评分位置: ES 内部                    评分位置: Java 应用层
```

## 技术栈

| 层级 | 选型 | 说明 |
|------|------|------|
| JDK | **Java 21** | 虚拟线程 (`Executors.newVirtualThreadPerTaskExecutor()`)，无信号量限流 |
| 框架 | **Spring Boot 3.3.x** | 自动配置、健康检查、指标采集 |
| 向量数据库 | **Milvus 2.4.x** | ANN 向量检索 + 标量过滤（替代 ES KNN + Bool Query） |
| 元数据存储 | **PostgreSQL** (可选) | 存储 answer/analysis/options 等 JSON 嵌套字段 |
| 缓存 | **Redis** (Lettuce) | 推荐结果缓存，TTL 5 分钟 |
| 向量服务 | **通义千问 text_to_vector** | 文本 → 768维向量 |
| 构建工具 | **Maven** | 依赖管理 + 打包 |

## 项目结构

```
recommend_question/
├── pom.xml                              # Maven 依赖
├── .env.example                         # 环境变量模板
├── README.md                            # 本文件
└── src/main/
    ├── resources/
    │   └── application.yml              # 全部配置
    └── java/com/questionrecommend/
        ├── Application.java             # 启动入口
        ├── config/                      # ★ 配置层
        │   ├── AppProperties.java       #   配置绑定（Milvus/Redis/向量服务/并发）
        │   ├── MilvusConfig.java        #   Milvus 客户端 + Schema 自动初始化
        │   ├── RedisConfig.java         #   Lettuce 连接池
        │   ├── ThreadPoolConfig.java    #   线程池 + 信号量
        │   └── GlobalVars.java          #   章节标签映射（超纲过滤用）
        ├── model/                       # ★ 数据模型
        │   ├── RecommendRequest.java    #   单条推荐请求
        │   ├── BatchRecommendRequest.java # 批量推荐请求
        │   ├── SimilarWithDataRequest.java # 带数据推荐请求
        │   ├── RecommendMode.java       #   推荐模式枚举 + 权重预设
        │   ├── CandidateResult.java     #   候选题内部表示
        │   ├── Question.java            #   JPA 实体（含向量/标量/元数据）
        │   └── ApiResponse.java         #   统一响应格式
        ├── client/                      # ★ 外部依赖客户端
        │   ├── MilvusClientWrapper.java #   Milvus SDK 封装（search/query/insert/delete）
        │   └── QwenVectorClient.java    #   通义千问向量化客户端
        ├── cache/
        │   └── RedisCacheService.java   # 缓存服务（Key 生成/截断/防御性设计）
        ├── service/                     # ★ 核心业务逻辑
        │   ├── RecallEngine.java        #   多路召回引擎（R0+R1 两路 → 融合 → 精排 → 去重 → 补量）
        │   ├── MilvusQueryService.java  #   R0/R1 单路 Milvus ANN 查询 + Jaccard 后处理
        │   ├── RankingService.java      #   ★ 精排（替代原 Painless 脚本的混合评分）
        │   ├── DeduplicationService.java#   SimHash + MD5 两级去重
        │   ├── RecommendService.java    #   请求编排（单条/批量）
        │   ├── VectorService.java       #   文本 → 向量
        │   ├── CrudService.java         #   题目 CRUD（同时写 Milvus + PostgreSQL）
        │   └── QuestionRepository.java  #   JPA 仓库
        ├── controller/                  # REST 控制器
        │   ├── RecommendController.java #   /recommend/similar, /similar_batch, /similarWithData
        │   └── QuestionCrudController.java # /elasticsearch/create, /delete, /search
        └── util/                        # 工具类
            ├── SimHashUtils.java        #   SimHash 64位指纹
            ├── JaccardUtils.java        #   Jaccard 相似度计算
            ├── HtmlCleanUtils.java      #   HTML 清洗（Jsoup 替代 BeautifulSoup）
            └── TextCleanUtils.java      #   文本清洗 + 分词
```

## 快速开始

### 前置条件

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | 运行环境 |
| Maven | 3.8+ | 构建工具 |
| Milvus | 2.4.x | 向量数据库 |
| Redis | 6.x+ | 结果缓存 |
| PostgreSQL (可选) | 14+ | 完整题目数据存储 |
| 通义千问向量服务 | - | 文本转向量 |

### 步骤 1: 启动 Milvus

```bash
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:2.4.0-latest
```

### 步骤 2: 配置环境变量

复制并修改 `.env.example`，或直接修改 `application.yml`：

```yaml
app:
  milvus:
    host: localhost:19530          # Milvus gRPC 地址
    collection: question_vectors   # 集合名（首次启动自动创建）
    vector-dimension: 768          # 向量维度（通义千问输出维度）
    index-type: HNSW               # 索引类型

  cache:
    enabled: true
    ttl-seconds: 300               # 缓存 5 分钟

  vector:
    url: http://127.0.0.1:8111/text_to_vector
```

### 步骤 3: 编译运行

```bash
cd recommend_question
mvn spring-boot:run
```

首次启动时 `MilvusConfig` 自动创建 `question_vectors` 集合和索引。

### 步骤 4: 数据迁移（从 ES 导入）

将 ES 数据导入 Milvus 的脚本可参考以下 Java 代码模式：

```java
// ES → Milvus 数据迁移（建议单独写迁移任务）
milvusClient.insert(records);  // 批量写入 Milvus
// 字段映射见 CrudService.buildMilvusRecord()
```

## API 接口

### 1. 单条推荐

**POST** `/recommend/similar`

```json
{
  "id": "1870740226240479234",
  "size": 10,
  "recommend_mode": "balanced",
  "disableQuestion": [],
  "difficulty": null,
  "type": []
}
```

### 2. 批量推荐

**POST** `/recommend/similar_batch`

```json
{
  "ids": ["1870740226240479234", "1332025"],
  "size": 10,
  "recommend_mode": "balanced"
}
```

### 3. 带数据推荐

**POST** `/recommend/similarWithData`

```json
{
  "data": {
    "id": "custom_001",
    "title": "...",
    "options": ["A", "B"],
    "vector": [0.1, 0.2, ...]
  },
  "size": 10,
  "recommend_mode": "custom",
  "similarity_weight": 0.5,
  "tag_weight": 0.2,
  "main_klg_weight": 0.3,
  "all_klg_weight": 0.2
}
```

### 4. 题目 CRUD

| 接口 | 方法 | 说明 |
|------|------|------|
| `/elasticsearch/create_question` | POST | 创建/更新题目（写 Milvus + PostgreSQL） |
| `/elasticsearch/delete_question` | POST | 删除题目 |
| `/elasticsearch/search_one_question` | POST | 查询单题 |

## Milvus Collection Schema

| 字段 | 类型 | 用途 |
|------|------|------|
| `id` | VarChar (PK) | 题目 ID |
| `vector` | FloatVector[768] | 题面向量（用于 ANN 检索） |
| `difficulty` | Int64 | 难度过滤 |
| `baseTypeId` | Int64 | 基础题型过滤 |
| `subjectTagCode` | VarChar | 学科（扁平化，替代 ES nested） |
| `phaseTagCode` | VarChar | 学段（扁平化） |
| `typeTagCode` | VarChar | 题型（扁平化） |
| `tagCodes` | Array[VarChar] | 标签列表（Jaccard 计算用） |
| `mainLlmKnowledge` | Array[VarChar] | 主知识点列表 |
| `allLlmKnowledge` | Array[VarChar] | 全部知识点列表 |
| `chapterIndex` | Int64 | 章节索引（超纲过滤） |
| `titleMd5` | VarChar | 题面 MD5（去重用） |
| `title` | VarChar[4096] | 题面文本 |

## 四种推荐模式

| 模式 | 向量权重 | 标签权重 | 主知识权重 | 全知识权重 | 说明 |
|------|:--------:|:--------:|:----------:|:----------:|------|
| `custom` | 用户传入 | 用户传入 | 用户传入 | 用户传入 | 完全自定义 |
| `balanced` | 0.30 | 0.15 | 0.35 | 0.20 | 均衡推荐 |
| `knowledge_strict` | 0.20 | 0.10 | 0.45 | 0.25 | 知识点强匹配（硬过滤） |
| `similarity_first` | 0.55 | 0.20 | 0.15 | 0.10 | 纯向量相似度优先 |

> `knowledge_strict` 模式下，若源题主知识点为空，自动降级为 `balanced`。

## 核心推荐流程

```
用户请求 → Redis 缓存检查 (命中则直接返回)
    │
    ▼ 未命中
Milvus 获取源题数据 (queryById)
    │
    ▼
┌── 并行两路召回（虚拟线程 + Semaphore）──────┐
│                                              │
├── R0_knowledge: Milvus ANN                   │
│   + Java Jaccard 后处理                      │
│   + 混合评分 (mainJaccard*0.6 + allJaccard*0.4) * 0.7 + cosine*0.3  │
│                                              │
├── R1_vector: Milvus ANN + expr 过滤          │
│   (学科/学段/题型/难度)                      │
│                                              │
└──────────────────────────────────────────────┘
    │
    ▼
统一大池子融合 (R0 路径奖励 +0.15)
    │
    ▼
多维度加权精排 (RankingService)
    │
    ▼
SimHash + MD5 两级去重
    │
    ▼
缺量补充 (最多2轮R1扩大召回)
    │
    ▼
写入 Redis 缓存 + 返回 TopK
```

## ES → Milvus 能力对照

| ES 功能 | Milvus 等价 | 实现位置 |
|---------|-------------|---------|
| KNN + cosineSimilarity | `search()` + `MetricType.COSINE` | `MilvusQueryService` |
| Painless script_score | ❌ 不支持 | **Java 层** `RankingService` + `MilvusQueryService` |
| nested 查询 | `expr` 表达式过滤 | `MilvusQueryService.buildFilterExpr()` |
| bool.must / filter | `expr and` 连接 | 同上 |
| bool.should 加分 | ❌ 不支持 → Java 层处理 | `RecallEngine` 融合阶段 |
| mget / get | `query()` / `queryById()` | `MilvusClientWrapper` |
| dense_vector(768) | `FloatVector(768)` | `MilvusConfig` Schema |
| 索引预热 | `loadCollection()` | `MilvusConfig` 启动时 |
| 全文检索 | ❌ 不支持（需求无影响） | - |

## 性能与并发

并发模型：**Java 21 虚拟线程**（无需信号量，虚拟线程在 IO 阻塞时自动 yield）

| 参数 | 默认值 | 说明 |
|------|:------:|------|
| `concurrency.batchChunkSize` | 25 | 每批虚拟线程处理题数 |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MILVUS_HOST` | `localhost:19530` | Milvus gRPC 地址 |
| `REDIS_HOST` | `127.0.0.1` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_TTL` | `300` | 缓存 TTL（秒） |
| `ENABLE_CACHE` | `true` | 缓存开关 |
| `QW_HOST` | `http://127.0.0.1:8111/text_to_vector` | 向量服务地址 |
| `PG_HOST` | `localhost` | PostgreSQL 主机 |
| `PG_PORT` | `5432` | PostgreSQL 端口 |
| `PG_DATABASE` | `question_bank` | PostgreSQL 数据库 |
| `VERBOSE_RECOMMEND_LOG` | `false` | 详细推荐日志 |

## 参考文档

- [Java+Milvus改造可行性评估.md](../Java+Milvus改造可行性评估.md) — 完整的迁移方案
- [原 Python 项目 README](../README.md) — 原版功能说明
