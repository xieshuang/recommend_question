package com.questionrecommend.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应格式 — 等价 Python format_response()
 * <p>
 * {
 *   "code": 0,
 *   "msg": "success",
 *   "data": {...},
 *   "diagnostics": {...}
 * }
 */
public class ApiResponse<T> {

    private int code;
    private String msg;
    private T data;
    private Map<String, Object> diagnostics;

    public ApiResponse() {
    }

    public ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    public ApiResponse<T> withDiagnostic(String key, Object value) {
        if (diagnostics == null) {
            diagnostics = new LinkedHashMap<>();
        }
        diagnostics.put(key, value);
        return this;
    }

    // ====== Getters and Setters ======

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public Map<String, Object> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(Map<String, Object> diagnostics) { this.diagnostics = diagnostics; }
}
