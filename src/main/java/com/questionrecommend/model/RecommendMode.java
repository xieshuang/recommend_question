package com.questionrecommend.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐模式枚举与权重预设 — 等价 Python MODE_WEIGHT_PRESETS
 * <p>
 * 四种推荐模式：
 * <ul>
 *   <li>{@link #CUSTOM} — 完全自定义权重</li>
 *   <li>{@link #BALANCED} — 均衡模式：向量 0.30, 标签 0.15, 主知识 0.35, 全知识 0.20</li>
 *   <li>{@link #KNOWLEDGE_STRICT} — 知识点强匹配：向量 0.20, 标签 0.10, 主知识 0.45, 全知识 0.25</li>
 *   <li>{@link #SIMILARITY_FIRST} — 相似度优先：向量 0.55, 标签 0.20, 主知识 0.15, 全知识 0.10</li>
 * </ul>
 */
public enum RecommendMode {

    CUSTOM("custom"),
    BALANCED("balanced"),
    KNOWLEDGE_STRICT("knowledge_strict"),
    SIMILARITY_FIRST("similarity_first");

    /** 所有合法模式名称集合 */
    public static final Set<String> VALID_MODES = Collections.unmodifiableSet(
            Arrays.stream(values()).map(RecommendMode::getModeName).collect(Collectors.toSet()));

    private final String modeName;

    RecommendMode(String modeName) {
        this.modeName = modeName;
    }

    public String getModeName() {
        return modeName;
    }

    /**
     * 根据模式名称获取枚举
     */
    public static RecommendMode fromString(String mode) {
        if (mode == null || mode.isEmpty()) return CUSTOM;
        for (RecommendMode rm : values()) {
            if (rm.modeName.equalsIgnoreCase(mode)) return rm;
        }
        return CUSTOM;
    }

    /**
     * 获取权重预设
     *
     * @return [simWeight, tagWeight, mainKlgWeight, allKlgWeight]
     */
    public double[] getWeightPreset() {
        return WEIGHT_PRESETS.getOrDefault(this, new double[]{0.3, 0.15, 0.35, 0.20});
    }

    /** 权重预设表 — 等价 Python MODE_WEIGHT_PRESETS */
    private static final Map<RecommendMode, double[]> WEIGHT_PRESETS = Map.of(
            BALANCED,           new double[]{0.30, 0.15, 0.35, 0.20},
            KNOWLEDGE_STRICT,   new double[]{0.20, 0.10, 0.45, 0.25},
            SIMILARITY_FIRST,   new double[]{0.55, 0.20, 0.15, 0.10}
    );

    /**
     * 解析权重 — 等价 Python resolve_weights()
     * <p>
     * 如果是预设模式，覆盖用户传入的自定义权重。
     * CUSTOM 模式使用用户传入值。
     *
     * @param mode          推荐模式
     * @param simWeight     用户传入的向量相似度权重
     * @param tagWeight     用户传入的标签权重
     * @param mainKlgWeight 用户传入的主要知识点权重
     * @param allKlgWeight  用户传入的全部知识点权重
     * @return [simWeight, tagWeight, mainKlgWeight, allKlgWeight, effectiveMode]
     */
    public static WeightResult resolveWeights(String mode,
                                                double simWeight, double tagWeight,
                                                double mainKlgWeight, double allKlgWeight) {
        RecommendMode rm = fromString(mode);
        if (rm == CUSTOM) {
            return new WeightResult(simWeight, tagWeight, mainKlgWeight, allKlgWeight, CUSTOM);
        }
        double[] preset = rm.getWeightPreset();
        return new WeightResult(preset[0], preset[1], preset[2], preset[3], rm);
    }

    /** 权重解析结果 */
    public record WeightResult(double simWeight, double tagWeight,
                                double mainKlgWeight, double allKlgWeight,
                                RecommendMode effectiveMode) {}
}
