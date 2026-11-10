package com.group.scm.modules.purchase.mapper;

import com.group.scm.common.mapper.DocumentMapper;
import com.group.scm.modules.purchase.entity.BizPr;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BizPrMapper extends DocumentMapper<BizPr> {
    @Update("UPDATE biz_pr SET status = 'CONVERTED', updated_at = NOW() WHERE id = #{id} AND status = 'APPROVED' AND deleted = 0")
    int markConvertedFromApproved(@Param("id") Long id);
}
