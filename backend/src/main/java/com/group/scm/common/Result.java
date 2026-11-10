package com.group.scm.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结构。
 *
 * <p>code：0=成功，非 0=业务错误码（如 4001=未登录、5000=系统异常）</p>
 */
@Data
public class Result<T> implements Serializable {
    private int code;
    private String msg;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMsg("ok");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> fail(String msg) {
        return fail(5000, msg);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}
