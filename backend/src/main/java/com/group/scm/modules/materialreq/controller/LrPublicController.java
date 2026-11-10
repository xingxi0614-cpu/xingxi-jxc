package com.group.scm.modules.materialreq.controller;

import com.group.scm.common.Result;
import com.group.scm.modules.materialreq.service.LrPublicService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 免登录领料申请提交接口（仅凭链接 token）。已在 SaTokenConfig 白名单中放行。
 * 只读库存(仅数量)、只写领料申请(计划)，不触碰登录态、不动库存。
 */
@Tag(name = "领料申请-免登录提交")
@RestController
@RequestMapping("/lr-public")
@RequiredArgsConstructor
public class LrPublicController {

    private final LrPublicService service;

    @GetMapping("/context")
    public Result<Map<String, Object>> context(@RequestParam String token) {
        return Result.ok(service.context(token));
    }

    @GetMapping("/materials")
    public Result<List<Map<String, Object>>> materials(@RequestParam String token,
                                                        @RequestParam(required = false) String keyword) {
        return Result.ok(service.searchMaterials(token, keyword));
    }

    @GetMapping("/stock")
    public Result<BigDecimal> stock(@RequestParam String token, @RequestParam Long materialId) {
        return Result.ok(service.stock(token, materialId));
    }

    @PostMapping("/submit")
    public Result<Map<String, Object>> submit(@RequestBody LrPublicService.SubmitDTO dto) {
        return Result.ok(service.submit(dto));
    }

    @PostMapping("/pp-submit")
    public Result<Map<String, Object>> ppSubmit(@RequestBody LrPublicService.PpSubmitDTO dto) {
        return Result.ok(service.ppSubmit(dto));
    }

    /** 免登录附件上传：一线提交后把照片挂到刚建的申请上（bizType=LR/PP）。 */
    @PostMapping(value = "/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<?> uploadAttachment(@RequestParam String token, @RequestParam String bizType,
                                      @RequestParam Long bizId, @RequestPart("file") MultipartFile file) {
        service.uploadAttachment(token, bizType, bizId, file);
        return Result.ok();
    }
}
