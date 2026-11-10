package com.group.scm.modules.attachment.controller;

import com.group.scm.common.Result;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.attachment.entity.BizAttachment;
import com.group.scm.modules.attachment.service.AttachmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 单据附件：继承母单角色和账套权限；读取含金额影像另需成本权限，删除限上传人或管理员。 */
@Tag(name = "单据附件")
@RestController
@RequestMapping("/attachment")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    @GetMapping
    public Result<List<BizAttachment>> list(@RequestParam String bizType, @RequestParam Long bizId) {
        return Result.ok(service.list(bizType, bizId));
    }

    @OpLog(module = "单据附件", action = "上传", recordBody = false)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<BizAttachment> upload(@RequestParam String bizType, @RequestParam Long bizId,
                                        @RequestPart("file") MultipartFile file) {
        return Result.ok(service.upload(bizType, bizId, file));
    }

    @OpLog(module = "单据附件", action = "下载", recordBody = false)
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id) {
        AttachmentService.Download d = service.open(id);
        BizAttachment a = d.meta();
        String encoded = URLEncoder.encode(a.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(AttachmentService.downloadMime(a)));
        if (d.length() > 0) builder.contentLength(d.length());
        return builder.body(d::writeTo);
    }

    @OpLog(module = "单据附件", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
