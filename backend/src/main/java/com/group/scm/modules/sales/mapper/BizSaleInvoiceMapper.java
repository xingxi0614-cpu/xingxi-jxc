package com.group.scm.modules.sales.mapper;

import com.group.scm.common.mapper.DocumentMapper;
import com.group.scm.modules.sales.entity.BizSaleInvoice;

public interface BizSaleInvoiceMapper extends DocumentMapper<BizSaleInvoice> {
    /** 来源在建单后不可修改，仅用来确定锁顺序；状态随后由 selectById 当前读复核。 */
    @org.apache.ibatis.annotations.Select("SELECT source_out_id FROM biz_sale_invoice WHERE id=#{id} AND deleted=0")
    Long sourceOutId(@org.apache.ibatis.annotations.Param("id") Long id);
}
