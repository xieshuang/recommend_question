package com.questionrecommend.service;

import com.questionrecommend.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 题目 PostgreSQL 仓库
 * <p>
 * 用于存储完整题目元数据（answer, analysis, options 等 JSON 嵌套数据）。
 * Milvus 只存储向量 + 标量字段，完整 CRUD 操作通过此仓库完成。
 */
@Repository
public interface QuestionRepository extends JpaRepository<Question, String> {
}
