package com.group.scm.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.Result;
import com.group.scm.modules.system.entity.SysReleaseNote;
import com.group.scm.modules.system.mapper.SysReleaseNoteMapper;
import com.group.scm.modules.system.service.ReleaseGuideService;
import lombok.Data;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "系统更新说明")
@RestController
@RequestMapping("/sys/release-notes")
@RequiredArgsConstructor
public class SysReleaseNoteController {

    private final SysReleaseNoteMapper releaseNoteMapper;
    private final ReleaseGuideService guideService;

    @GetMapping("/latest")
    public Result<SysReleaseNote> latest() {
        QueryWrapper<SysReleaseNote> qw = visibleNotes()
                .eq("popup", 1)
                .orderByDesc("published_at")
                .orderByDesc("sort")
                .orderByDesc("id")
                .last("LIMIT 1");
        return Result.ok(releaseNoteMapper.selectOne(qw));
    }

    @GetMapping
    public Result<IPage<SysReleaseNote>> page(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "20") long size) {
        QueryWrapper<SysReleaseNote> qw = visibleNotes()
                .orderByDesc("published_at")
                .orderByDesc("sort")
                .orderByDesc("id");
        return Result.ok(releaseNoteMapper.selectPage(new Page<>(current, size), qw));
    }

    /** 本版本的逐条带看条目 + 我看过哪些。登录即可。 */
    @GetMapping("/guide")
    public Result<Map<String, Object>> guide(@RequestParam String version) {
        return Result.ok(guideService.guide(version));
    }

    /** 记一条"看过了"（只记当前登录人，不能替别人记）。 */
    @PostMapping("/guide/read")
    public Result<Void> read(@RequestBody GuideReadDTO dto) {
        if (dto == null) return Result.fail("参数不能为空");
        guideService.markRead(dto.getVersion(), dto.getItemKey());
        return Result.ok();
    }

    /** 必看条目还没看完的人。管理员/账套管理员/财务可查，范围限可见账套（service 内校验）。 */
    @GetMapping("/guide/unread")
    public Result<Map<String, Object>> unread(@RequestParam String version) {
        return Result.ok(guideService.unread(version));
    }

    @GetMapping("/{id}")
    public Result<SysReleaseNote> detail(@PathVariable Long id) {
        QueryWrapper<SysReleaseNote> qw = visibleNotes().eq("id", id);
        return Result.ok(releaseNoteMapper.selectOne(qw));
    }

    private QueryWrapper<SysReleaseNote> visibleNotes() {
        return new QueryWrapper<SysReleaseNote>()
                .eq("deleted", 0)
                .eq("status", 1);
    }

    @Data
    public static class GuideReadDTO {
        private String version;
        private String itemKey;
    }
}
