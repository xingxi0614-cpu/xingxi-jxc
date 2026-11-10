package com.group.scm.common;

import lombok.Getter;

/**
 * 业务异常 - 抛出后被 GlobalExceptionHandler 捕获并返回友好的错误响应。
 *
 * <p>用例：</p>
 * <pre>
 * if (qty &gt; remain) throw new BizException("本次入库不能超过未入库 " + remain);
 * if (po == null)     throw new BizException(4040, "订单不存在");
 * </pre>
 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(String msg) {
        this(5000, msg);
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}
