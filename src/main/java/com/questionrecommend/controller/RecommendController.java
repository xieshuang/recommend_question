package com.questionrecommend.controller;

import com.questionrecommend.model.*;
import com.questionrecommend.service.RecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 推荐接口控制器 — 等价 Python recommend 路由
 * <p>
 * 提供接口：
 * <ul>
 *   <li>POST /recommend/similar — 单条推荐（等价 Python /recommend/similar）</li>
 *   <li>POST /recommend/similar_batch — 批量推荐（等价 Python /recommend/similar_batch）</li>
 *   <li>POST /recommend/similarWithData — 带数据推荐（等价 Python /recommend/similarWithData）</li>
 * </ul>
 */
@RestController
@RequestMapping("/recommend")
public class RecommendController {

    private static final Logger log = LoggerFactory.getLogger(RecommendController.class);

    private final RecommendService recommendService;

    public RecommendController(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    /**
     * 单条推荐
     * <p>
     * 等价 Python: POST /recommend/similar
     */
    @PostMapping("/similar")
    public ApiResponse<Object> similar(@RequestBody RecommendRequest request) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("========== [推荐请求] req={} q={} mode={} size={} ==========",
                requestId, request.getQuestionId(), request.getMode(), request.getSize());
        return recommendService.recommend(request);
    }

    /**
     * 批量推荐
     * <p>
     * 等价 Python: POST /recommend/similar_batch
     */
    @PostMapping("/similar_batch")
    public ApiResponse<Object> similarBatch(@RequestBody BatchRecommendRequest request) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("========== [批量推荐请求] req={} ids={} size={} ==========",
                requestId, request.getIds() != null ? request.getIds().size() : 0, request.getSize());
        return recommendService.recommendBatch(request);
    }

    /**
     * 带数据推荐
     * <p>
     * 等价 Python: POST /recommend/similarWithData
     */
    @PostMapping("/similarWithData")
    public ApiResponse<Object> similarWithData(@RequestBody SimilarWithDataRequest request) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        log.info("========== [带数据推荐请求] req={} mode={} size={} ==========",
                requestId, request.getMode(), request.getSize());
        return recommendService.recommendWithData(request);
    }
}
