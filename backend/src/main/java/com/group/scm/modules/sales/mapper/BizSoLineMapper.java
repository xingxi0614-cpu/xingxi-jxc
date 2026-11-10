package com.group.scm.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.sales.entity.BizSoLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BizSoLineMapper extends BaseMapper<BizSoLine> {

    @Update("""
        UPDATE biz_so_line SET out_qty = out_qty + #{qty}
        WHERE id = #{id} AND out_qty + #{qty} <= qty
    """)
    int addOutQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    @Update("""
        UPDATE biz_so_line SET invoice_qty = invoice_qty + #{qty}
        WHERE id = #{id}
    """)
    int addInvoiceQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);
}
