package com.questionrecommend.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionrecommend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 缓存服务 — 等价 Python redis_client.py
 * <p>
 * 防御性设计（与 Python 版本保持一致）：
 * <ul>
 *   <li>异常静默降级：get/set/mget 异常时返回 null，不抛异常到上层</li>
 *   <li>参数边界防护：限制 key 长度（512B）、value 大小（1MB）、空列表处理</li>
 * </ul>
 */
@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 单条缓存最大 1MB */
    private static final long VALUE_SIZE_MAX = 1024 * 1024;
    /** Key 最大长度（字节） */
    private static final int KEY_LENGTH_MAX = 512;

    private final RedisTemplate<String, Object> redisTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate,
                              AppProperties properties,
                              ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取缓存
     *
     * @param key 缓存键
     * @return 缓存数据，未命中或异常返回 null
     */
    public List<Map<String, Object>> get(String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) return null;

            // 反序列化
            if (value instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> result = (List<Map<String, Object>>) list;
                return result;
            }
            // 尝试 JSON 反序列化
            String json = objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Redis get 异常 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 批量读取缓存 — 等价 Python mget
     *
     * @param keys 缓存键列表
     * @return key → 数据 映射（未命中不包含）
     */
    public Map<String, List<Map<String, Object>>> mget(List<String> keys) {
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        try {
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null) return result;

            for (int i = 0; i < keys.size(); i++) {
                Object value = i < values.size() ? values.get(i) : null;
                if (value != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) value;
                    result.put(keys.get(i), data);
                }
            }
        } catch (Exception e) {
            log.warn("Redis mget 异常 keys_count={}: {}", keys.size(), e.getMessage());
        }
        return result;
    }

    /**
     * 写入缓存
     *
     * @param key   缓存键
     * @param value 缓存数据
     * @param ttl   过期时间（秒）
     * @return 是否写入成功
     */
    public boolean set(String key, List<Map<String, Object>> value, int ttl) {
        if (key == null || key.isEmpty()) return false;
        if (value == null || value.isEmpty()) return false;

        try {
            // 检查 value 大小
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > VALUE_SIZE_MAX) {
                log.warn("Redis 缓存 value 过大 ({}B)，跳过写入 key={}", json.length(), key);
                return false;
            }

            redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.warn("Redis set 异常 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 生成缓存 key — 等价 Python get_cache_key()
     * <p>
     * 格式: similar:{id}:s{size}:rm{mode}:sw{sim}:tw{tag}:mw{main}:aw{all}:d{diff}:...
     * 长度超过 512 字节时自动截断并 MD5 后缀。
     */
    public String getCacheKey(String questionId, int size, String mode,
                                double simWeight, double tagWeight,
                                double mainKlgWeight, double allKlgWeight,
                                Integer difficulty, String sortedTypes,
                                List<String> mainLlmKnowledge,
                                List<String> allLlmKnowledge,
                                List<String> disableQuestion) {
        List<String> parts = new ArrayList<>();
        parts.add("similar");
        parts.add(questionId != null ? questionId : "null");
        parts.add("s" + size);
        parts.add("rm" + (mode != null ? mode : "custom"));

        // 权重（仅非默认值添加）
        if (simWeight != 0.6) parts.add("sw" + simWeight);
        if (tagWeight != 0.4) parts.add("tw" + tagWeight);
        if (mainKlgWeight != 0.0) parts.add("mw" + mainKlgWeight);
        if (allKlgWeight != 0.0) parts.add("aw" + allKlgWeight);
        if (difficulty != null) parts.add("d" + difficulty);

        // 题型
        if (sortedTypes != null && !sortedTypes.isEmpty()) {
            parts.add("qt" + sortedTypes);
        }

        // 禁用 ID 列表（MD5 截断）
        if (disableQuestion != null && !disableQuestion.isEmpty()) {
            String idsStr = disableQuestion.stream().sorted().collect(Collectors.joining(","));
            parts.add("dq" + md5Hex(idsStr).substring(0, 8));
        }

        // 知识点覆盖（MD5 截断）
        if (mainLlmKnowledge != null) {
            String klgStr = mainLlmKnowledge.stream().sorted().collect(Collectors.joining(","));
            parts.add("mk" + md5Hex(klgStr).substring(0, 8));
        }
        if (allLlmKnowledge != null) {
            String klgStr = allLlmKnowledge.stream().sorted().collect(Collectors.joining(","));
            parts.add("ak" + md5Hex(klgStr).substring(0, 8));
        }

        String key = String.join(":", parts);

        // 长度防护
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length > KEY_LENGTH_MAX) {
            String prefix = new String(keyBytes, 0, KEY_LENGTH_MAX - 16, StandardCharsets.UTF_8);
            byte[] suffixBytes = new byte[keyBytes.length - KEY_LENGTH_MAX + 16];
            System.arraycopy(keyBytes, KEY_LENGTH_MAX - 16, suffixBytes, 0, suffixBytes.length);
            String suffix = md5Hex(new String(suffixBytes, StandardCharsets.UTF_8)).substring(0, 8);
            key = prefix + ":" + suffix;
        }

        return key;
    }

    /**
     * 删除缓存
     */
    public boolean delete(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.delete(key));
        } catch (Exception e) {
            log.warn("Redis delete 异常 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    // ====== 内部工具 ======

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String md5Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(input));
        }
    }
}
