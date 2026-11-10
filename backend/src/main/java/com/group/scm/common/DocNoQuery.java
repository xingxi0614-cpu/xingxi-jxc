package com.group.scm.common;

import java.util.Arrays;
import java.util.List;

/**
 * 单据号查询口径：区分「模糊搜一个」和「精确查一批」。
 *
 * <p><b>为什么要有这个：</b>月末结账的对账检查会列出一批问题单据，用户点「去处理」时
 * 需要跳到对应列表并<b>只显示这几张单</b>。原先跳转按状态筛（写死 PENDING），
 * 结果不是筛空就是把全部单据都列出来、分不清哪张有问题（用户反馈过）。</p>
 *
 * <p><b>做法：</b>复用各列表已有的 keyword 参数，不新增接口参数——
 * keyword 里带逗号就视为「按单号集合精确查」，否则维持原来的模糊匹配。
 * 单据号本身不含逗号（形如 ABC-SA-20260821-0006），不会误判；
 * 用户在搜索框手输逗号也能当批量查单号用，属于顺带的增强。</p>
 */
public final class DocNoQuery {

    private DocNoQuery() {}

    /**
     * 把 keyword 解析成单号集合。
     *
     * @return 逗号分隔时返回去空后的单号列表；否则返回 {@code null}，调用方走原有模糊匹配
     */
    public static List<String> asDocNoList(String keyword) {
        if (keyword == null || keyword.indexOf(',') < 0) return null;
        List<String> list = Arrays.stream(keyword.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return list.isEmpty() ? null : list;
    }
}
