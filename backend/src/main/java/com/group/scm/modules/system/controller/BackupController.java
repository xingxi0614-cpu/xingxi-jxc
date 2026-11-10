package com.group.scm.modules.system.controller;

import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.system.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** 数据备份/导出（主动备份到本地）。 */
@RestController
@RequestMapping("/sys/backup")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    /** 导出完整数据 Excel（账套管理员/管理员，按可见账套范围）。 */
    @GetMapping("/export-excel")
    @OpLog(module = "数据备份", action = "导出完整数据Excel", recordBody = false)
    public ResponseEntity<byte[]> exportExcel() {
        SecurityUtil.requireAccountAdmin();
        byte[] data = backupService.exportExcel();
        return download(data, "完整数据备份-" + LocalDate.now() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /** 下载整库 SQL 备份（仅系统管理员，含全部账套，用于技术性整库恢复）。 */
    @GetMapping("/export-sql")
    @OpLog(module = "数据备份", action = "下载整库SQL备份", recordBody = false)
    public ResponseEntity<byte[]> exportSql() {
        SecurityUtil.requireAdmin();
        byte[] data = backupService.exportSqlGz();
        return download(data, "整库备份-" + LocalDate.now() + ".sql.gz", "application/gzip");
    }

    private ResponseEntity<byte[]> download(byte[] data, String fileName, String contentType) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }
}
