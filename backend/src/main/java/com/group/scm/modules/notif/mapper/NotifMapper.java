package com.group.scm.modules.notif.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.notif.entity.Notif;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 待办查询。
 *
 * 🔴🔴 本文件里的账套过滤是【安全防线】,2026-07-31 加固过两处,改之前先看清楚为什么:
 *
 * ① 账套判定必须 fail-closed(漏了就拦住),不许写成 fail-open(漏了就放行)。
 *    原来是 `#{allOrg} OR n.org_id IS NULL OR n.org_id IN (...)` ——
 *    多出来的 `org_id IS NULL` 意味着:任何一条没写账套的角色待办,
 *    会出现在【全系统所有持有该角色的人】的待办和铃铛里,不分公司,
 *    泄漏内容是「审批节点名 + 别家公司的单据号」,而且不报任何错。
 *    现在删掉了这一支;推送侧 NotifService.pushToRole 也加了 orgId 非空断言,
 *    两头都堵。**不要因为"某条待办看不见了"就把 IS NULL 加回来** ——
 *    看不见说明它本来就是条脏数据,该去修产生它的地方。
 *
 * ② 角色子查询必须过滤 `r.deleted = 0 AND r.status = 1`。
 *    否则角色被停用/退役后,原持有人照旧收得到该角色的待办。
 *    (这里原本是全站唯一一处漏过滤的地方,菜单查询 SysMenuMapper.listByUser 一直是有的)
 *
 * 上线前核对过:角色型待办都带 org_id、涉及的角色都启用未删,所以这两项收紧对存量数据无影响。
 */
public interface NotifMapper extends BaseMapper<Notif> {

    @Select("""
        <script>
        SELECT * FROM notif n
        WHERE n.type = 'TODO'
          AND n.is_read = 0
          AND (
              n.user_id = #{userId}
              OR (
                  n.user_id IS NULL
                  AND n.role_code IN (
                      SELECT r.code
                      FROM sys_role r
                      JOIN sys_user_role ur ON ur.role_id = r.id
                      WHERE ur.user_id = #{userId}
                        AND r.deleted = 0 AND r.status = 1
                  )
                  AND ( #{allOrg} OR n.org_id IN
                      <foreach item='oid' collection='orgIds' open='(' separator=',' close=')'>#{oid}</foreach>
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM notif_read nr
                      WHERE nr.notif_id = n.id AND nr.user_id = #{userId}
                  )
              )
          )
        ORDER BY n.created_at DESC
        LIMIT #{limit}
        </script>
    """)
    List<Notif> listMyUnread(@Param("userId") Long userId, @Param("orgIds") List<Long> orgIds,
                             @Param("allOrg") boolean allOrg, @Param("limit") int limit);

    @Select("""
        <script>
        SELECT COUNT(*) FROM notif n
        WHERE n.type = 'TODO'
          AND n.is_read = 0
          AND (
              n.user_id = #{userId}
              OR (
                  n.user_id IS NULL
                  AND n.role_code IN (
                      SELECT r.code
                      FROM sys_role r
                      JOIN sys_user_role ur ON ur.role_id = r.id
                      WHERE ur.user_id = #{userId}
                        AND r.deleted = 0 AND r.status = 1
                  )
                  AND ( #{allOrg} OR n.org_id IN
                      <foreach item='oid' collection='orgIds' open='(' separator=',' close=')'>#{oid}</foreach>
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM notif_read nr
                      WHERE nr.notif_id = n.id AND nr.user_id = #{userId}
                  )
              )
          )
        </script>
    """)
    long countMyUnread(@Param("userId") Long userId, @Param("orgIds") List<Long> orgIds,
                       @Param("allOrg") boolean allOrg);

    @Update("""
        UPDATE notif
        SET is_read = 1, read_at = NOW()
        WHERE id = #{id} AND user_id = #{userId}
    """)
    int markUserRead(@Param("userId") Long userId, @Param("id") Long id);

    @Insert("""
        INSERT IGNORE INTO notif_read (notif_id, user_id, read_at)
        SELECT n.id, #{userId}, NOW()
        FROM notif n
        WHERE n.id = #{id}
          AND n.user_id IS NULL
          AND n.is_read = 0
          AND n.role_code IN (
              SELECT r.code
              FROM sys_role r
              JOIN sys_user_role ur ON ur.role_id = r.id
              WHERE ur.user_id = #{userId}
                AND r.deleted = 0 AND r.status = 1
          )
    """)
    int markRoleRead(@Param("userId") Long userId, @Param("id") Long id);

    @Update("""
        UPDATE notif
        SET is_read = 1, read_at = NOW()
        WHERE user_id = #{userId}
          AND is_read = 0
    """)
    int markAllUserRead(@Param("userId") Long userId);

    @Insert("""
        <script>
        INSERT IGNORE INTO notif_read (notif_id, user_id, read_at)
        SELECT n.id, #{userId}, NOW()
        FROM notif n
        WHERE n.type = 'TODO'
          AND n.user_id IS NULL
          AND n.is_read = 0
          AND n.role_code IN (
              SELECT r.code
              FROM sys_role r
              JOIN sys_user_role ur ON ur.role_id = r.id
              WHERE ur.user_id = #{userId}
                AND r.deleted = 0 AND r.status = 1
          )
          AND ( #{allOrg} OR n.org_id IN
              <foreach item='oid' collection='orgIds' open='(' separator=',' close=')'>#{oid}</foreach>
          )
          AND NOT EXISTS (
              SELECT 1
              FROM notif_read nr
              WHERE nr.notif_id = n.id AND nr.user_id = #{userId}
          )
        </script>
    """)
    int markAllRoleRead(@Param("userId") Long userId, @Param("orgIds") List<Long> orgIds,
                        @Param("allOrg") boolean allOrg);

    @Update("""
        UPDATE notif SET is_read = 1, read_at = NOW()
        WHERE biz_type = #{bizType}
          AND biz_id = #{bizId}
          AND type = 'TODO'
          AND is_read = 0
    """)
    int closeBusinessTodos(@Param("bizType") String bizType, @Param("bizId") Long bizId);
}
