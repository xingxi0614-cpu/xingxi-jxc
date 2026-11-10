package com.group.scm.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.inventory.entity.InvBalance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface InvBalanceMapper extends BaseMapper<InvBalance> {

    /** 加 FOR UPDATE 行级锁，防止并发出入库导致单价算错 */
    @Select("SELECT * FROM inv_balance WHERE material_id = #{matId} AND warehouse_id = #{whId} FOR UPDATE")
    InvBalance findForUpdate(@Param("matId") Long matId, @Param("whId") Long whId);

    /** 按公司核算:锁住同一公司、同一物料的全部仓库余额行(按仓库 id 升序,固定加锁顺序)。 */
    @Select("""
        SELECT b.* FROM inv_balance b JOIN md_warehouse w ON w.id = b.warehouse_id
        WHERE b.material_id = #{matId} AND w.org_id = #{orgId}
        ORDER BY b.warehouse_id FOR UPDATE
    """)
    List<InvBalance> findOrgMaterialForUpdate(@Param("orgId") Long orgId, @Param("matId") Long matId);

    @Select("SELECT * FROM inv_balance WHERE material_id = #{matId} AND warehouse_id = #{whId}")
    InvBalance find(@Param("matId") Long matId, @Param("whId") Long whId);

    @Select("""
        <script>
        SELECT b.*,
               b.material_id AS materialId,
               b.warehouse_id AS warehouseId,
               b.avg_price AS avgPrice,
               b.updated_at AS updatedAt,
               m.code matCode,
               m.name matName,
               m.spec matSpec,
               m.unit matUnit,
               w.name whName
        FROM inv_balance b
        LEFT JOIN md_material m ON m.id = b.material_id
        LEFT JOIN md_warehouse w ON w.id = b.warehouse_id
        WHERE (#{matId} IS NULL OR b.material_id = #{matId})
          AND (#{whId} IS NULL OR b.warehouse_id = #{whId})
          <if test="terms != null and terms.size() > 0">
          AND (<foreach collection="terms" item="words" separator=" OR ">
            (<foreach collection="words" item="t" separator=" AND ">
              (m.code LIKE CONCAT('%', #{t}, '%') OR m.name LIKE CONCAT('%', #{t}, '%') OR m.spec LIKE CONCAT('%', #{t}, '%'))
            </foreach>)
          </foreach>)
          </if>
          AND w.org_id IN
          <foreach collection="orgIds" item="orgId" open="(" separator="," close=")">
            #{orgId}
          </foreach>
          AND (#{includeZero} = 1 OR b.qty != 0)
        ORDER BY m.code, w.code
        </script>
    """)
    List<java.util.Map<String, Object>> queryWithName(@Param("matId") Long matId,
                                                       @Param("whId") Long whId,
                                                       @Param("terms") List<List<String>> terms,
                                                       @Param("orgIds") List<Long> orgIds,
                                                       @Param("includeZero") Integer includeZero);
}
