package com.group.scm.modules.notif.service;

import com.group.scm.common.BizException;
import com.group.scm.modules.notif.entity.Notif;
import com.group.scm.modules.notif.mapper.NotifMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知/待办服务。
 *
 * <p>典型场景：</p>
 * <ul>
 *   <li>采购提交单据 → 推送给当前审批岗位角色 → 对应人员登录看到待办</li>
 *   <li>刘杰审批通过 → 给张三个人发信息：您的 PR 已通过</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NotifService {

    private final NotifMapper mapper;

    /** 待办性质:等我审批 / 等我确认(要我做决定) */
    public static final String KIND_APPROVAL = "APPROVAL";
    /** 待办性质:退回给我改(驳回、退回草稿) */
    public static final String KIND_REWORK = "REWORK";

    /**
     * 标题最长存多少字。
     * 🔴 notif.title 列宽 500,而驳回意见(wf_record.comment)本身就能写 500 字,
     *    拼上「单据 xxx 被驳回，请修改后重新提交：」的前缀必然超长 → 插入报错 →
     *    【整个驳回事务回滚,审批人点驳回直接失败】(2026-07-31 审计)。
     *    列宽已放宽到 500,这里再留一道写入侧的截断兜底:两道防线都要有,
     *    因为以后谁把标题模板改长一点,不该由数据库来告诉他"驳回失败"。
     */
    private static final int TITLE_MAX = 480;

    /** 推送给特定用户(普通消息,不进待办) */
    public void pushToUser(Long userId, String title, String bizType, String bizNo, Long bizId) {
        pushToUser(userId, "INFO", null, title, bizType, bizNo, bizId);
    }

    /**
     * 推送给特定用户并进入待办。
     * 不带 kind 的旧签名一律当作「退回要我改」——现存的个人待办
     * (驳回、退回草稿)全是这一类;要推"等我审批"请用带 kind 的重载。
     */
    public void pushTodoToUser(Long userId, String title, String bizType, String bizNo, Long bizId) {
        pushToUser(userId, "TODO", KIND_REWORK, title, bizType, bizNo, bizId);
    }

    /** 推送给特定用户并进入待办,明确标注待办性质 */
    public void pushTodoToUser(Long userId, String kind, String title, String bizType, String bizNo, Long bizId) {
        pushToUser(userId, "TODO", kind, title, bizType, bizNo, bizId);
    }

    private void pushToUser(Long userId, String type, String kind, String title,
                            String bizType, String bizNo, Long bizId) {
        Notif n = new Notif();
        n.setUserId(userId);
        n.setType(type);
        n.setTodoKind("TODO".equals(type) ? kind : null);
        n.setTitle(clip(title));
        n.setBizType(bizType);
        n.setBizNo(bizNo);
        n.setBizId(bizId);
        n.setIsRead(0);
        mapper.insert(n);
    }

    /**
     * 推送给某个角色（该角色用户都能看到），并标记业务单据所属账套。
     * orgId 用于查询时按账套隔离，避免不同账套同角色互相看到对方待办。
     *
     * 不带 kind 的旧签名一律当作「等我审批/确认」——按角色广播的待办
     * 本质都是"这个岗位上的人来处理一下"。
     */
    public void pushToRole(String roleCode, Long orgId, String title, String bizType, String bizNo, Long bizId) {
        pushToRole(roleCode, orgId, KIND_APPROVAL, title, bizType, bizNo, bizId);
    }

    public void pushToRole(String roleCode, Long orgId, String kind,
                           String title, String bizType, String bizNo, Long bizId) {
        // 🔴 账套必须有值,不许为 null。
        //    待办查询已改成 fail-closed(org_id 对不上就不给看),一条 org_id 为 null 的
        //    角色待办会变成【谁也看不见】的死条目;而在改成 fail-closed 之前,它是
        //    【全系统所有持有该角色的人都看得见】的跨公司泄漏。两种都不能接受,
        //    所以在入口就拦住,让漏传的人当场报错,而不是静默留下一条坏数据。
        if (orgId == null) {
            throw new BizException(5000, "推送角色待办必须带账套(roleCode=" + roleCode + ", bizType=" + bizType + ")");
        }
        Notif n = new Notif();
        n.setRoleCode(roleCode);
        n.setOrgId(orgId);
        n.setType("TODO");
        n.setTodoKind(kind);
        n.setTitle(clip(title));
        n.setBizType(bizType);
        n.setBizNo(bizNo);
        n.setBizId(bizId);
        n.setIsRead(0);
        mapper.insert(n);
    }

    /** 标题超长就截断加省略号,绝不让它把整个业务事务带崩 */
    private String clip(String title) {
        if (title == null) return "";
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX - 1) + "…";
    }

    /**
     * 我的未读待办。
     * @param orgIds 当前用户可见账套；allOrg 为 true（管理员/全局数据权限）时忽略账套过滤。
     */
    public List<Notif> myUnread(Long userId, List<Long> orgIds, boolean allOrg, int limit) {
        return mapper.listMyUnread(userId, safeOrgIds(orgIds), allOrg, limit);
    }

    public long unreadCount(Long userId, List<Long> orgIds, boolean allOrg) {
        return mapper.countMyUnread(userId, safeOrgIds(orgIds), allOrg);
    }

    public void markRead(Long userId, Long id) {
        if (userId == null || id == null) return;
        mapper.markUserRead(userId, id);
        mapper.markRoleRead(userId, id);
    }

    public void markAllRead(Long userId, List<Long> orgIds, boolean allOrg) {
        if (userId == null) return;
        mapper.markAllUserRead(userId);
        mapper.markAllRoleRead(userId, safeOrgIds(orgIds), allOrg);
    }

    /** foreach 不接受空集合，这里兜底为含 0 的列表（不会命中任何账套）。 */
    private List<Long> safeOrgIds(List<Long> orgIds) {
        return (orgIds == null || orgIds.isEmpty()) ? List.of(0L) : orgIds;
    }

    public void closeBusinessTodos(String bizType, Long bizId) {
        if (bizType == null || bizId == null) return;
        mapper.closeBusinessTodos(bizType, bizId);
    }
}
