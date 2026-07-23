package com.project.phm.adapter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一 API 响应包装。
 */
@Schema(description = "统一 API 响应")
public class ApiResult<T> {

    @Schema(description = "业务状态码，200 表示成功")
    private int code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "航新数据源查询元数据")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SourceInfo hangxin;

    @Schema(description = "633数据源查询元数据")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SourceInfo sansan;

    @Schema(description = "本机数据源查询元数据")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SourceInfo local;

    public ApiResult() {}

    public ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public SourceInfo getHangxin() { return hangxin; }
    public void setHangxin(SourceInfo hangxin) { this.hangxin = hangxin; }
    public SourceInfo getSansan() { return sansan; }
    public void setSansan(SourceInfo sansan) { this.sansan = sansan; }
    public SourceInfo getLocal() { return local; }
    public void setLocal(SourceInfo local) { this.local = local; }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(200, "success", data);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
