package com.group.scm.modules.notif.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.notif.entity.Notif;
import com.group.scm.modules.notif.service.NotifService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "通知/待办")
@RestController
@RequestMapping("/notif")
@RequiredArgsConstructor
public class NotifController {

    private final NotifService notifService;
    private final OrgScopeService orgScope;

    /** 管理员或拥有全局数据权限时，不按账套过滤待办 */
    private boolean allOrg() {
        return SecurityUtil.isAdmin() || SecurityUtil.hasDataScope("ALL");
    }

    @GetMapping("/my")
    public Result<List<Notif>> my(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(notifService.myUnread(SecurityUtil.uid(), orgScope.visibleOrgIds(), allOrg(), limit));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.ok(Map.of("count", notifService.unreadCount(SecurityUtil.uid(), orgScope.visibleOrgIds(), allOrg())));
    }

    @PostMapping("/{id}/read")
    public Result<?> read(@PathVariable Long id) {
        notifService.markRead(SecurityUtil.uid(), id);
        return Result.ok();
    }

    @PostMapping("/read-all")
    public Result<?> readAll() {
        notifService.markAllRead(SecurityUtil.uid(), orgScope.visibleOrgIds(), allOrg());
        return Result.ok();
    }
}
