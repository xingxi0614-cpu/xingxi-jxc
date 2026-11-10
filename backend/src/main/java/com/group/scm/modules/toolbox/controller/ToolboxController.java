package com.group.scm.modules.toolbox.controller;

import com.group.scm.common.Result;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.toolbox.entity.ToolTask;
import com.group.scm.modules.toolbox.service.ToolboxService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 工具箱。所有接口都要登录 + portal.tool.use 权限(Service 内强制),
 * 且一律只能碰【自己公司】的任务 —— 隔离逻辑见 ToolboxService 顶部注释。
 */
@Tag(name = "工具箱")
@RestController
@RequestMapping("/toolbox")
@RequiredArgsConstructor
public class ToolboxController {

    private final ToolboxService service;

    /** 我能不能用工具箱(前端据此决定显不显示这个模块) */
    @GetMapping("/permission")
    public Result<Map<String, Boolean>> permission() {
        return Result.ok(Map.of("canUse", service.canUse()));
    }

    /** 有哪些工具 */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools() {
        return Result.ok(service.tools());
    }

    /** 我公司的任务列表 */
    @GetMapping("/tasks")
    public Result<List<ToolTask>> tasks() {
        return Result.ok(service.list());
    }

    /** 单个任务(前端轮询进度) */
    @GetMapping("/tasks/{id}")
    public Result<ToolTask> task(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /** 提交任务。body: { toolCode, title, params } */
    @OpLog(module = "工具箱", action = "生成")
    @PostMapping("/tasks")
    public Result<ToolTask> submit(@RequestBody Map<String, Object> body) {
        String toolCode = String.valueOf(body.getOrDefault("toolCode", ""));
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        Object params = body.get("params");
        String paramsJson;
        try {
            paramsJson = params == null ? "{}" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            paramsJson = "{}";
        }
        return Result.ok(service.submit(toolCode, title, paramsJson));
    }

    /** 即时型工具:直接算直接返回,不建任务不生成文件。body: { toolCode, params } */
    @PostMapping("/instant")
    public Result<Map<String, Object>> instant(@RequestBody Map<String, Object> body) {
        String toolCode = String.valueOf(body.getOrDefault("toolCode", ""));
        String paramsJson;
        try {
            Object params = body.get("params");
            paramsJson = params == null ? "{}" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) { paramsJson = "{}"; }
        return Result.ok(service.runInstant(toolCode, paramsJson));
    }

    /** 带文件上传的提交(multipart) */
    @OpLog(module = "工具箱", action = "生成")
    @PostMapping("/tasks/upload")
    public Result<ToolTask> submitWithFile(@RequestParam String toolCode,
                                           @RequestParam(required = false) String title,
                                           @RequestParam(required = false) String params,
                                           @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return Result.ok(service.submitWithFile(toolCode, title, params == null ? "{}" : params, file));
    }

    @OpLog(module = "工具箱", action = "删除")
    @DeleteMapping("/tasks/{id}")
    public Result<?> remove(@PathVariable Long id) {
        service.remove(id);
        return Result.ok();
    }

    /**
     * 下载结果文件。
     * ⚠️ 归属校验在 Service 里做(按 org_id + 该任务登记过的文件名),
     *    这里只负责把文件写回去,不做任何路径拼接。
     */
    @GetMapping("/tasks/{id}/file")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id, @RequestParam String name) {
        Path p = service.download(id, name);
        String fn = URLEncoder.encode(p.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(p));
    }
}
