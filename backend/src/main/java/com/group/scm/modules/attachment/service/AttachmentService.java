package com.group.scm.modules.attachment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.attachment.entity.BizAttachment;
import com.group.scm.modules.attachment.mapper.BizAttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单据附件：文件存在服务器本机目录(SCM_ATTACHMENT_DIR,默认 /data/scm/attachments)，库里只存元数据。
 * 下载走后端中转(校验账套可见性)，目录不对外暴露,不能被 nginx 直接访问。
 *
 * 【磁盘上的文件名不用上传者给的原名】原名可能带 "../"、斜杠等,直接拼路径就能写到附件目录外面。
 * 所以落盘名 = 单据类型/单据id/随机串.扩展名,三段全由服务端生成;原名只存数据库,下载时再还给用户。
 * 列 cos_key 沿用旧名,现在存的是相对附件目录的路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final BizAttachmentMapper mapper;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbc;

    @Value("${SCM_ATTACHMENT_DIR:/data/scm/attachments}") private String baseDir;

    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(
            "jpg", "jpeg", "png", "webp", "pdf", "xls", "xlsx", "doc", "docx", "zip");
    /** 单据类型 → 主表（org_id 校验用）。PP/LR 为免登录提交的采购计划/领料申请。 */
    private static final Map<String, String> BIZ_TABLES = Map.ofEntries(
            Map.entry("PO", "biz_po"), Map.entry("RECV", "biz_recv"), Map.entry("INV", "biz_invoice"),
            Map.entry("OREQ", "biz_out_req"), Map.entry("OUT", "biz_out"), Map.entry("RETURN", "biz_return"),
            Map.entry("LOAN", "biz_loan"), Map.entry("TRANSFER", "biz_transfer"), Map.entry("STOCKTAKE", "biz_stocktake"),
            Map.entry("PP", "pp_request"), Map.entry("LR", "lr_request"));

    /** 相对路径 → 附件目录下的绝对路径;规范化后仍必须落在附件目录里(防路径穿越,双保险)。 */
    private Path resolve(String key) {
        Path root = Path.of(baseDir).toAbsolutePath().normalize();
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new BizException(4003, "附件路径非法");
        return p;
    }

    /** 校验单据存在且当前用户可见，返回单据 org_id。 */
    private Long assertBizVisible(String bizType, Long bizId, boolean readContent) {
        String table = BIZ_TABLES.get(bizType);
        if (table == null) throw new BizException("不支持的单据类型：" + bizType);
        AttachmentAccess.require(bizType, readContent);
        List<Long> orgs = jdbc.queryForList("SELECT org_id FROM " + table + " WHERE id = ? AND deleted = 0", Long.class, bizId);
        if (orgs.isEmpty()) throw new BizException("单据不存在");
        orgScope.assertOrgVisible(orgs.get(0));
        return orgs.get(0);
    }

    public List<BizAttachment> list(String bizType, Long bizId) {
        Long orgId = assertBizVisible(bizType, bizId, true);
        List<BizAttachment> rows = mapper.selectList(new QueryWrapper<BizAttachment>()
                .eq("biz_type", bizType).eq("biz_id", bizId).eq("org_id", orgId).orderByDesc("id"));
        for (BizAttachment r : rows) {
            if (r.getCreatedBy() != null) {
                r.setCreatedByName(jdbc.queryForList("SELECT name FROM sys_user WHERE id = ?", String.class, r.getCreatedBy())
                        .stream().findFirst().orElse(null));
            }
        }
        return rows;
    }

    public BizAttachment upload(String bizType, Long bizId, MultipartFile file) {
        Long orgId = assertBizVisible(bizType, bizId, false);
        return store(bizType, bizId, orgId, file);
    }

    /** 免登录上传（采购计划/领料申请提交页用）：调用方已凭 token 校验单据归属，这里不做登录态校验。 */
    public BizAttachment uploadPublic(String bizType, Long bizId, Long orgId, MultipartFile file) {
        return store(bizType, bizId, orgId, file);
    }

    /**
     * 配额(安全审查 S09)。正常使用时单张单据通常只有几个附件、十几 MB,
     * 下面的上限留了十几倍余量,正常使用碰不到,只挡"持续往里灌"。
     */
    static final int MAX_FILES_PER_DOC = 50;
    static final long MAX_BYTES_PER_DOC = 200L * 1024 * 1024;
    static final long MAX_BYTES_PER_ORG_DAY = 1024L * 1024 * 1024;

    /** 校验+存盘+落库（登录/免登录共用）。 */
    private BizAttachment store(String bizType, Long bizId, Long orgId, MultipartFile file) {
        if (!BIZ_TABLES.containsKey(bizType) || bizId == null) throw new BizException("不支持的单据类型：" + bizType);
        if (file == null || file.isEmpty()) throw new BizException("请选择文件");
        if (file.getSize() > MAX_SIZE) throw new BizException("单个附件不能超过 20MB");
        String name = file.getOriginalFilename() == null ? "附件" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXT.contains(ext)) throw new BizException("只支持图片/PDF/Office/压缩包：" + String.join("、", ALLOWED_EXT));
        assertQuota(bizType, bizId, orgId, file.getSize());

        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            log.warn("读取上传附件失败", e);
            throw new BizException("读取上传文件失败，请重试");
        }
        String mime = AttachmentContentGuard.check(ext, data);

        // bizType 已在 assertBizVisible / 调用方按白名单校验过,bizId 是数字,随机串和扩展名(白名单)由服务端定
        String key = bizType + "/" + bizId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件再改名:写到一半断电/出错,不会留下一个残缺的"正式"附件
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, data);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            // 异常原文带服务器路径,只进日志,不回给前端
            log.error("附件写盘失败 bizType={} bizId={}", bizType, bizId, e);
            throw new BizException("附件上传失败，请稍后重试；仍失败请联系管理员（详情已记入服务器日志）");
        }
        BizAttachment a = new BizAttachment();
        a.setBizType(bizType);
        a.setBizId(bizId);
        a.setOrgId(orgId);
        a.setFileName(name);
        a.setSizeBytes((long) data.length);
        a.setMime(mime);
        a.setCosKey(key);
        mapper.insert(a);
        return a;
    }

    /** 每张单据的个数/总量、每家公司每天的总量;已删除的也算(文件保留着,占的是真空间)。 */
    private void assertQuota(String bizType, Long bizId, Long orgId, long incoming) {
        Map<String, Object> doc = jdbc.queryForMap("""
                SELECT COUNT(*) c, COALESCE(SUM(size_bytes), 0) b FROM biz_attachment
                WHERE biz_type = ? AND biz_id = ?
                """, bizType, bizId);
        if (((Number) doc.get("c")).intValue() >= MAX_FILES_PER_DOC) {
            throw new BizException("这张单据的附件已达 " + MAX_FILES_PER_DOC + " 个上限，请合并后再传，或联系管理员");
        }
        if (((Number) doc.get("b")).longValue() + incoming > MAX_BYTES_PER_DOC) {
            throw new BizException("这张单据的附件总量已接近 " + (MAX_BYTES_PER_DOC >> 20) + "MB 上限，请压缩后再传，或联系管理员");
        }
        Long today = jdbc.queryForObject("""
                SELECT COALESCE(SUM(size_bytes), 0) FROM biz_attachment
                WHERE org_id = ? AND created_at >= CURDATE()
                """, Long.class, orgId);
        if (today != null && today + incoming > MAX_BYTES_PER_ORG_DAY) {
            log.warn("公司 {} 当日附件上传量超限:已 {} 字节", orgId, today);
            throw new BizException("本公司今天上传的附件已超过 " + (MAX_BYTES_PER_ORG_DAY >> 20) + "MB，请明天再传，或联系管理员");
        }
    }

    public BizAttachment meta(Long id) {
        return authorizedMeta(id, true);
    }

    private BizAttachment authorizedMeta(Long id, boolean readContent) {
        BizAttachment a = mapper.selectById(id);
        if (a == null) throw new BizException("附件不存在");
        orgScope.assertOrgVisible(a.getOrgId());
        Long motherOrg = assertBizVisible(a.getBizType(), a.getBizId(), readContent);
        if (!motherOrg.equals(a.getOrgId())) throw new BizException(4003, "附件与单据所属账套不一致");
        return a;
    }

    /**
     * 下载:先校验权限、打开文件(出错时还能回 JSON 提示),再边读边写给浏览器。
     * 不整个读进内存:几个人同时下 20MB 附件会成倍占堆。
     */
    public Download open(Long id) {
        BizAttachment a = meta(id);
        try {
            Path p = resolve(a.getCosKey());
            return new Download(a, Files.newInputStream(p), Files.size(p));
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("附件读取失败 id={}", id, e);
            throw new BizException("附件下载失败，文件可能已丢失；请联系管理员（详情已记入服务器日志）");
        }
    }

    /** 下载用的类型按扩展名认定(历史附件存的是上传方自报的类型,不可信)。 */
    public static String downloadMime(BizAttachment a) {
        String n = a.getFileName() == null ? "" : a.getFileName();
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).toLowerCase() : "";
        return AttachmentContentGuard.mimeOf(ext);
    }

    /** 打开着的下载:写完(或出错)后关流。 */
    public record Download(BizAttachment meta, InputStream in, long length) {
        public void writeTo(java.io.OutputStream out) throws IOException {
            try (InputStream src = in) {
                src.transferTo(out);
            }
        }
    }

    public void delete(Long id) {
        BizAttachment a = authorizedMeta(id, false);
        Long uid = SecurityUtil.uid();
        if (!SecurityUtil.isAdmin() && !SecurityUtil.isAccountAdmin()
                && (uid == null || !uid.equals(a.getCreatedBy()))) {
            throw new BizException("只有上传人或管理员可以删除附件");
        }
        mapper.deleteById(id); // 逻辑删除；文件保留(可恢复，也便于审计追溯)
    }
}
