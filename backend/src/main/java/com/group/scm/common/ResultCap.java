package com.group.scm.common;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 列表「条数上限」的显式提示(2026-10-19)。
 *
 * <p>为什么要:报表、收付款、销售查询这些接口为防一次拉爆内存,SQL 里写了 LIMIT 500 / 1000 / 5000。
 * 碰到上限时多出来的行被悄悄丢掉,页面上的合计、汇总卡也只按前 N 行算,用户完全不知道——
 * 出入库流水就是这样(只给最近 200 条,实际早已远超这个数),用户反馈"像被隐藏了一样"。</p>
 *
 * <p>做法:查出的行数到了上限,就在响应头里标 {@value #HEADER}: 上限值;前端 api/request.ts 统一读这个头,
 * 弹出"只显示了前 N 条,请缩小查询条件"。各接口只需在返回前包一层 {@link #mark},不改返回结构。
 * 行数正好等于上限也算(可能恰好 N 条,也可能更多),宁可多提醒一次。</p>
 */
public final class ResultCap {

    public static final String HEADER = "X-Result-Truncated";

    private ResultCap() {
    }

    public static <T> List<T> mark(List<T> rows, int limit) {
        if (rows != null && rows.size() >= limit
                && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletResponse resp = attrs.getResponse();
            if (resp != null && !resp.isCommitted()) resp.setHeader(HEADER, String.valueOf(limit));
        }
        return rows;
    }
}
