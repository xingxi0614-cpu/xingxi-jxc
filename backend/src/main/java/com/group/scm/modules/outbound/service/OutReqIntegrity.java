package com.group.scm.modules.outbound.service;

import com.group.scm.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 申请始终先于下游单据加锁；进度为零不能代表没有历史业务。 */
@Component
@RequiredArgsConstructor
public class OutReqIntegrity {
    private final JdbcTemplate jdbc;

    public void lockSourceForOut(Long outId) {
        var ids = jdbc.queryForList("SELECT source_req_id FROM biz_out WHERE id=?", Long.class, outId);
        if (!ids.isEmpty()) lockRequest(ids.get(0));
    }

    public void lockSourceForReturn(Long returnId) {
        var ids = jdbc.queryForList("SELECT source_req_id FROM biz_return WHERE id=?", Long.class, returnId);
        if (!ids.isEmpty()) lockRequest(ids.get(0));
    }

    private void lockRequest(Long id) {
        if (id != null) jdbc.queryForList("SELECT id FROM biz_out_req WHERE id=? FOR UPDATE", Long.class, id);
    }

    /** 调用方已持有申请头锁；当前读可看到等待锁期间刚提交的下游。 */
    public void assertNoDownstream(Long reqId) {
        var outs = jdbc.queryForList("SELECT doc_no FROM biz_out WHERE source_req_id=? AND deleted=0 ORDER BY id FOR UPDATE", String.class, reqId);
        if (!outs.isEmpty()) throw new BizException("该出库申请已有材料出库单 " + String.join("、", outs) + "，不能取消审批、修改或删除；请保留业务来源，需要调整数量请走修订");
        var returns = jdbc.queryForList("SELECT doc_no FROM biz_return WHERE source_req_id=? AND deleted=0 ORDER BY id FOR UPDATE", String.class, reqId);
        if (!returns.isEmpty()) throw new BizException("该出库申请已有退料单 " + String.join("、", returns) + "，不能取消审批、修改或删除，请保留业务来源");
    }
}
