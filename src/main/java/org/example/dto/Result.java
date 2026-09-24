package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 统一 API 响应包装。
 */
@Getter
@Setter
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public boolean isSuccess() {
        return code == 200;
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String message) {
        return fail(500, message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
