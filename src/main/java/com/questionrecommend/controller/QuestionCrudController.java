package com.questionrecommend.controller;

import com.questionrecommend.model.ApiResponse;
import com.questionrecommend.service.CrudService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 题目 CRUD 接口控制器 — 等价 Python elasticsearch 路由
 * <p>
 * 提供接口：
 * <ul>
 *   <li>POST /elasticsearch/create_question — 创建/更新题目</li>
 *   <li>POST /elasticsearch/delete_question — 删除题目</li>
 *   <li>POST /elasticsearch/search_one_question — 查询单题</li>
 * </ul>
 * <p>
 * 同时操作 Milvus（向量+标量）和 PostgreSQL（完整元数据）。
 */
@RestController
@RequestMapping("/elasticsearch")
public class QuestionCrudController {

    private static final Logger log = LoggerFactory.getLogger(QuestionCrudController.class);

    private final CrudService crudService;

    public QuestionCrudController(CrudService crudService) {
        this.crudService = crudService;
    }

    /**
     * 创建/更新题目
     * <p>
     * 等价 Python: POST /elasticsearch/create_question
     */
    @PostMapping("/create_question")
    public ApiResponse<Object> createQuestion(@RequestBody Map<String, Object> questionData) {
        try {
            Map<String, Object> result = crudService.createOrUpdate(questionData);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("创建题目失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "创建题目失败: " + e.getMessage());
        }
    }

    /**
     * 删除题目
     * <p>
     * 等价 Python: POST /elasticsearch/delete_question
     */
    @PostMapping("/delete_question")
    public ApiResponse<Object> deleteQuestion(@RequestBody Map<String, Object> request) {
        String questionId = request.get("id") != null ? request.get("id").toString() : null;
        if (questionId == null) {
            return ApiResponse.error(400, "id 不能为空");
        }
        try {
            Map<String, Object> result = crudService.delete(questionId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("删除题目失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "删除题目失败: " + e.getMessage());
        }
    }

    /**
     * 查询单题
     * <p>
     * 等价 Python: POST /elasticsearch/search_one_question
     */
    @PostMapping("/search_one_question")
    public ApiResponse<Object> searchOneQuestion(@RequestBody Map<String, Object> request) {
        String questionId = request.get("id") != null ? request.get("id").toString() : null;
        if (questionId == null) {
            return ApiResponse.error(400, "id 不能为空");
        }
        try {
            Map<String, Object> question = crudService.getById(questionId);
            if (question == null || question.isEmpty()) {
                return ApiResponse.error(404, "题目不存在: " + questionId);
            }
            return ApiResponse.success(question);
        } catch (Exception e) {
            log.error("查询题目失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "查询题目失败: " + e.getMessage());
        }
    }
}
