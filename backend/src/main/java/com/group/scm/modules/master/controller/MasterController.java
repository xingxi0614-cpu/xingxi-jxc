package com.group.scm.modules.master.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.master.entity.MdMaterial;
import com.group.scm.modules.master.entity.MdMaterialCat;
import com.group.scm.modules.master.entity.MdProject;
import com.group.scm.modules.master.entity.MdSupplier;
import com.group.scm.modules.master.entity.MdWarehouse;
import com.group.scm.modules.master.mapper.MdMaterialCatMapper;
import com.group.scm.modules.master.mapper.MdMaterialMapper;
import com.group.scm.modules.master.mapper.MdProjectMapper;
import com.group.scm.modules.master.mapper.MdSupplierMapper;
import com.group.scm.modules.master.mapper.MdWarehouseMapper;
import com.group.scm.modules.master.service.MasterImportTemplateService;
import com.group.scm.modules.master.service.MaterialMasterImportService;
import com.group.scm.modules.master.service.ProjectMasterImportService;
import com.group.scm.modules.master.service.SupplierMasterImportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主数据：供应商 / 物料 / 仓库 通用 CRUD。
 */
@Tag(name = "主数据")
@RestController
@RequestMapping("/master")
@RequiredArgsConstructor
public class MasterController {

    private final MdSupplierMapper supplierMapper;
    private final MdMaterialMapper materialMapper;
    private final MdMaterialCatMapper materialCatMapper;
    private final MdWarehouseMapper warehouseMapper;
    private final MdProjectMapper projectMapper;
    private final MaterialMasterImportService materialImportService;
    private final SupplierMasterImportService supplierImportService;
    private final ProjectMasterImportService projectImportService;
    private final com.group.scm.common.service.DocNoService docNoService;
    private final MasterImportTemplateService importTemplateService;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbcTemplate;

    /* ============ 供应商 ============ */

    @GetMapping("/suppliers")
    public Result<IPage<MdSupplier>> listSuppliers(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) Integer isShared,
                                                    @RequestParam(required = false) Integer pool,
                                                    @RequestParam(required = false) Integer status) {
        supplierImportService.ensureSupplierSchema();
        QueryWrapper<MdSupplier> qw = new QueryWrapper<>();
        applySupplierMasterScope(qw, orgId, isShared, pool != null && pool == 1);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("name", k)
                    .or().like("code", k)
                    .or().like("short_name", k)
                    .or().like("supplier_category", k)
                    .or().like("supplier_type", k)
                    .or().like("finance_org", k));
        }
        // 档案页的「启用/已停用/全部」页签(2026-10-17):不传 status = 原来的行为(只看启用);
        // 传 -1 = 全部;传 0/1 = 只看停用/启用。原来停用的供应商在档案列表里完全看不到,想重新启用都找不到。
        if (status == null) qw.eq("status", 1);
        else if (status >= 0) qw.eq("status", status);
        qw.orderByAsc("code");
        IPage<MdSupplier> page = supplierMapper.selectPage(new Page<>(current, size), qw);
        return Result.ok(page);
    }

    @GetMapping("/suppliers/all")
    public Result<List<MdSupplier>> allSuppliers(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long orgId,
                                                  @RequestParam(required = false) Integer isShared) {
        supplierImportService.ensureSupplierSchema();
        // 供应商集团池（2026-09-17 定恢复）：单据选供应商全库可选；
        // 客户(is_customer=1)仍按账套隔离，不随池子外泄姓名/电话/地址
        QueryWrapper<MdSupplier> qw = new QueryWrapper<MdSupplier>().eq("status", 1);
        applySupplierSelectionScope(qw);
        if (orgId != null) qw.eq("org_id", orgId);
        if (isShared != null) qw.eq("is_shared", isShared);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("name", k)
                    .or().like("code", k)
                    .or().like("short_name", k)
                    .or().like("supplier_category", k)
                    .or().like("supplier_type", k)
                    .or().like("org_name", k));
        }
        List<MdSupplier> list = supplierMapper.selectList(qw.orderByAsc("code"));
        return Result.ok(list);
    }

    @PostMapping(value = "/suppliers/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<SupplierMasterImportService.ImportResult> importSuppliers(@RequestPart("file") MultipartFile file) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(supplierImportService.importExcel(file));
    }

    @GetMapping("/suppliers/import-template")
    public ResponseEntity<byte[]> supplierImportTemplate() {
        SecurityUtil.requireAccountAdmin();
        return excelTemplate("供应商档案导入模板.xlsx", importTemplateService.supplierTemplate());
    }

    @GetMapping("/suppliers/next-code")
    public Result<String> nextSupplierCode() {
        SecurityUtil.requireAccountAdmin();
        supplierImportService.ensureSupplierSchema();
        return Result.ok(generateNextSupplierCode());
    }

    @PostMapping("/suppliers")
    public Result<?> createSupplier(@RequestBody MdSupplier s) {
        SecurityUtil.requireAccountAdmin();
        supplierImportService.ensureSupplierSchema();
        normalizeSupplier(s);
        Long existingId = findSupplierDuplicate(s, null);
        if (existingId != null) {
            MdSupplier dup = supplierMapper.selectById(existingId);
            throw new BizException("该供应商已存在（" + dup.getCode() + " " + dup.getName()
                    + "，归属 " + (dup.getOrgName() == null ? "其他公司" : dup.getOrgName())
                    + "），集团池内可直接选用，请勿重复添加");
        }
        supplierMapper.insert(s);
        grantSupplierScope(s.getId(), s.getOrgId());
        return Result.ok(Map.of("id", s.getId(), "created", true));
    }

    @PutMapping("/suppliers/{id}")
    public Result<?> updateSupplier(@PathVariable Long id, @RequestBody MdSupplier s) {
        SecurityUtil.requireAccountAdmin();
        supplierImportService.ensureSupplierSchema();
        MdSupplier existing = supplierMapper.selectById(id);
        if (existing == null) throw new BizException(4040, "供应商不存在");
        assertOrgWritable(existing.getOrgId(), "无权维护其他账套的供应商");
        if (s.getCode() == null || s.getCode().isBlank()) s.setCode(existing.getCode());
        normalizeSupplier(s);
        Long duplicateId = findSupplierDuplicate(s, id);
        if (duplicateId != null) {
            throw new BizException("供应商编码或名称与已有档案重复（集团池全局唯一），请直接选用已有供应商");
        }
        s.setId(id);
        supplierMapper.updateById(s);
        grantSupplierScope(id, s.getOrgId());
        return Result.ok();
    }

    @OpLog(module = "基础资料", action = "批量授权供应商给组织")
    @PutMapping("/suppliers/batch-assign-org")
    public Result<?> batchAssignSupplierOrg(@RequestBody BatchAssignOrgDTO dto) {
        SecurityUtil.requireAccountAdmin();
        if (dto == null || dto.ids == null || dto.ids.isEmpty()) return Result.fail("请选择要授权的供应商");
        if (dto.orgId == null) return Result.fail("请选择目标组织");
        String orgName = orgNameById(dto.orgId);
        if (orgName == null) throw new BizException("目标组织不存在或已停用");
        assertOrgWritable(dto.orgId, "无权授权到该组织");
        int added = 0;
        for (Long supplierId : dto.ids) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM md_supplier_org_scope WHERE supplier_id = ? AND org_id = ?",
                    Integer.class, supplierId, dto.orgId);
            if (exists != null && exists > 0) continue;
            MdSupplier existing = supplierMapper.selectById(supplierId);
            if (existing == null) continue;
            assertOrgWritable(existing.getOrgId(), "无权操作其他账套的供应商");
            jdbcTemplate.update("INSERT INTO md_supplier_org_scope (supplier_id, org_id) VALUES (?, ?)",
                    supplierId, dto.orgId);
            added++;
        }
        return Result.ok(Map.of("added", added, "skipped", dto.ids.size() - added, "targetOrg", orgName));
    }

    @OpLog(module = "基础资料", action = "取消供应商授权")
    @DeleteMapping("/suppliers/{id}/scope/{orgId}")
    public Result<?> removeSupplierScope(@PathVariable Long id, @PathVariable Long orgId) {
        SecurityUtil.requireAccountAdmin();
        MdSupplier existing = supplierMapper.selectById(id);
        if (existing == null) throw new BizException(4040, "供应商不存在");
        assertOrgWritable(existing.getOrgId(), "无权操作该供应商");
        jdbcTemplate.update("DELETE FROM md_supplier_org_scope WHERE supplier_id = ? AND org_id = ?", id, orgId);
        return Result.ok(Map.of("removed", true, "orgName", String.valueOf(orgNameById(orgId))));
    }

    @OpLog(module = "基础资料", action = "删除供应商")
    @DeleteMapping("/suppliers/{id}")
    public Result<?> deleteSupplier(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        supplierImportService.ensureSupplierSchema();
        MdSupplier existing = supplierMapper.selectById(id);
        if (existing == null) return Result.ok();
        assertOrgWritable(existing.getOrgId(), "无权删除其他账套的供应商");
        jdbcTemplate.update("""
                UPDATE md_supplier
                   SET status = 0,
                       deleted = id,
                       updated_at = NOW()
                 WHERE id = ? AND deleted = 0
                """, id);
        jdbcTemplate.update("DELETE FROM md_supplier_org_scope WHERE supplier_id = ?", id);
        return Result.ok();
    }

    /* ============ 物料 ============ */

    @GetMapping("/material-cats")
    public Result<List<MdMaterialCat>> listMaterialCats(@RequestParam(defaultValue = "false") Boolean includeDisabled,
                                                         @RequestParam(required = false) String keyword) {
        materialImportService.ensureMaterialCategorySchema();
        QueryWrapper<MdMaterialCat> qw = new QueryWrapper<>();
        if (!Boolean.TRUE.equals(includeDisabled)) qw.eq("status", 1);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("code", keyword).or().like("name", keyword));
        }
        qw.orderByAsc("parent_id").orderByAsc("sort").orderByAsc("code");
        return Result.ok(materialCatMapper.selectList(qw));
    }

    @GetMapping("/materials")
    public Result<IPage<MdMaterial>> listMaterials(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String code,
                                                    @RequestParam(required = false) String name,
                                                    @RequestParam(required = false) String spec,
                                                    @RequestParam(required = false) String materialType,
                                                    @RequestParam(required = false) Long catId,
                                                    @RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) Integer isShared,
                                                    @RequestParam(required = false) Integer pool,
                                                    @RequestParam(defaultValue = "true") Boolean includeChildren,
                                                    @RequestParam(defaultValue = "false") Boolean includeDisabled,
                                                    @RequestParam(required = false) Integer status) {
        materialImportService.ensureMaterialOrgSchema();
        QueryWrapper<MdMaterial> qw = new QueryWrapper<>();
        // 档案页的「启用/已停用/全部」页签(2026-10-17):不传 status = 原来的行为(由 includeDisabled 决定);
        // 传 -1 = 全部;传 0/1 = 只看停用/启用
        boolean withDisabled = status != null || Boolean.TRUE.equals(includeDisabled);
        applyMaterialFilters(qw, keyword, code, name, spec, materialType, catId, orgId, isShared, includeChildren, withDisabled,
                pool != null && pool == 1);
        if (status != null && status >= 0) qw.eq("status", status);
        qw.orderByAsc("code");
        IPage<MdMaterial> page = materialMapper.selectPage(new Page<>(current, size), qw);
        return Result.ok(page);
    }

    @GetMapping("/materials/all")
    public Result<List<MdMaterial>> allMaterials(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long catId,
                                                  @RequestParam(required = false) String materialType,
                                                  @RequestParam(required = false) Long orgId,
                                                  @RequestParam(required = false) Integer isShared,
                                                  @RequestParam(defaultValue = "true") Boolean includeChildren,
                                                  @RequestParam(defaultValue = "false") Boolean includeDisabled) {
        materialImportService.ensureMaterialOrgSchema();
        // 集团池模型：选单据用，全库可选（pool=true 跳过组织可见性过滤）
        QueryWrapper<MdMaterial> qw = new QueryWrapper<>();
        applyMaterialFilters(qw, keyword, null, null, null, materialType, catId, orgId, isShared, includeChildren, includeDisabled, true);
        qw.orderByAsc("code");
        List<MdMaterial> list = materialMapper.selectList(qw);
        return Result.ok(list);
    }

    @GetMapping("/materials/next-code")
    public Result<String> nextMaterialCode(@RequestParam Long catId) {
        SecurityUtil.requireAccountAdmin();
        materialImportService.ensureMaterialOrgSchema();
        return Result.ok(generateNextMaterialCode(catId));
    }

    @PostMapping(value = "/materials/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<MaterialMasterImportService.ImportResult> importMaterials(@RequestPart("file") MultipartFile file) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(materialImportService.importExcel(file));
    }

    @GetMapping("/materials/import-template")
    public ResponseEntity<byte[]> materialImportTemplate() {
        SecurityUtil.requireAccountAdmin();
        return excelTemplate("物料档案导入模板.xlsx", importTemplateService.materialTemplate());
    }

    @PostMapping("/materials")
    public Result<?> createMaterial(@RequestBody MdMaterial m) {
        SecurityUtil.requireAccountAdmin();
        materialImportService.ensureMaterialOrgSchema();
        normalizeMaterial(m);
        Long existingId = findMaterialDuplicate(m, null);
        if (existingId != null) {
            MdMaterial dup = materialMapper.selectById(existingId);
            throw new BizException("该物料已存在（" + dup.getCode() + " " + dup.getName()
                    + "，归属 " + (orgNameById(dup.getOrgId()) == null ? "其他公司" : orgNameById(dup.getOrgId()))
                    + "），集团池内可直接选用，请勿重复添加");
        }
        materialMapper.insert(m);
        grantMaterialScope(m.getId(), m.getOrgId());
        return Result.ok(Map.of("id", m.getId(), "created", true));
    }

    @PutMapping("/materials/{id}")
    public Result<?> updateMaterial(@PathVariable Long id, @RequestBody MdMaterial m) {
        SecurityUtil.requireAccountAdmin();
        materialImportService.ensureMaterialOrgSchema();
        MdMaterial existing = materialMapper.selectById(id);
        if (existing == null) throw new BizException(4040, "物料不存在");
        assertOrgWritable(existing.getOrgId(), "无权维护其他账套的物料");
        normalizeMaterial(m);
        Long duplicateId = findMaterialDuplicate(m, id);
        if (duplicateId != null) {
            throw new BizException("物料编码或名称/规格/单位与已有档案重复（集团池全局唯一），请直接选用已有物料");
        }
        m.setId(id);
        materialMapper.updateById(m);
        grantMaterialScope(id, m.getOrgId());
        return Result.ok();
    }

    @OpLog(module = "基础资料", action = "取消物料授权")
    @DeleteMapping("/materials/{id}/scope/{orgId}")
    public Result<?> removeMaterialScope(@PathVariable Long id, @PathVariable Long orgId) {
        SecurityUtil.requireAccountAdmin();
        MdMaterial existing = materialMapper.selectById(id);
        if (existing == null) throw new BizException(4040, "物料不存在");
        assertOrgWritable(existing.getOrgId(), "无权操作该物料");
        jdbcTemplate.update("DELETE FROM md_material_org_scope WHERE material_id = ? AND org_id = ?", id, orgId);
        return Result.ok(Map.of("removed", true, "orgName", String.valueOf(orgNameById(orgId))));
    }

    @OpLog(module = "基础资料", action = "批量删除物料")
    @DeleteMapping("/materials/batch")
    public Result<?> batchDeleteMaterial(@RequestBody BatchAssignOrgDTO dto) {
        SecurityUtil.requireAccountAdmin();
        if (dto == null || dto.ids == null || dto.ids.isEmpty()) return Result.fail("请选择要删除的物料");
        int deleted = 0, blocked = 0;
        for (Long id : dto.ids) {
            MdMaterial m = materialMapper.selectById(id);
            if (m == null) continue;
            assertOrgWritable(m.getOrgId(), "无权删除其他账套的物料");
            // 检查是否有业务引用
            Integer refCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM biz_po_line WHERE material_id = ?", Integer.class, id);
            if (refCount != null && refCount > 0) {
                blocked++;
                continue;
            }
            // 软删除：更新 org_id 保持原样，删授权范围
            jdbcTemplate.update("DELETE FROM md_material_org_scope WHERE material_id = ?", id);
            // 与单条删除同一写法:deleted 写本行 id,不写 1(理由见 deleteProject 处的说明)。
            // 批量这条原来还在用 deleteById(deleted=1),是漏网的。
            jdbcTemplate.update("""
                    UPDATE md_material
                       SET status = 0,
                           deleted = id,
                           updated_at = NOW()
                     WHERE id = ? AND deleted = 0
                    """, id);
            deleted++;
        }
        return Result.ok(Map.of("deleted", deleted, "blocked", blocked,
                "hint", blocked > 0 ? "有 " + blocked + " 条物料已被业务单据引用，无法删除" : ""));
    }

    @OpLog(module = "基础资料", action = "批量授权物料给组织")
    @PutMapping("/materials/batch-assign-org")
    public Result<?> batchAssignMaterialOrg(@RequestBody BatchAssignOrgDTO dto) {
        SecurityUtil.requireAccountAdmin();
        if (dto == null || dto.ids == null || dto.ids.isEmpty()) return Result.fail("请选择要授权的物料");
        if (dto.orgId == null) return Result.fail("请选择目标组织");
        String orgName = orgNameById(dto.orgId);
        if (orgName == null) throw new BizException("目标组织不存在或已停用");
        assertOrgWritable(dto.orgId, "无权授权到该组织");
        // 物料 org_id 不变，仅写入授权关系表
        int added = 0;
        for (Long materialId : dto.ids) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM md_material_org_scope WHERE material_id = ? AND org_id = ?",
                    Integer.class, materialId, dto.orgId);
            if (exists != null && exists > 0) continue; // 已授权，跳过
            MdMaterial existing = materialMapper.selectById(materialId);
            if (existing == null) continue;
            assertOrgWritable(existing.getOrgId(), "无权操作其他账套的物料");
            jdbcTemplate.update("INSERT INTO md_material_org_scope (material_id, org_id) VALUES (?, ?)",
                    materialId, dto.orgId);
            added++;
        }
        return Result.ok(Map.of("added", added, "skipped", dto.ids.size() - added, "targetOrg", orgName));
    }

    @OpLog(module = "基础资料", action = "删除物料")
    @DeleteMapping("/materials/{id}")
    public Result<?> deleteMaterial(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        MdMaterial existing = materialMapper.selectById(id);
        if (existing == null) return Result.ok();
        assertOrgWritable(existing.getOrgId(), "无权删除其他账套的物料");
        jdbcTemplate.update("""
                UPDATE md_material
                   SET status = 0,
                       deleted = id,
                       updated_at = NOW()
                 WHERE id = ? AND deleted = 0
                """, id);
        jdbcTemplate.update("DELETE FROM md_material_org_scope WHERE material_id = ?", id);
        return Result.ok();
    }

    /* ============ 仓库 ============ */

    @GetMapping("/warehouses")
    public Result<List<MdWarehouse>> listWarehouses(@RequestParam(defaultValue = "false") Boolean includeDisabled,
                                                    @RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) String warehouseType,
                                                    @RequestParam(required = false) String keyword) {
        QueryWrapper<MdWarehouse> qw = new QueryWrapper<>();
        if (!Boolean.TRUE.equals(includeDisabled)) qw.eq("status", 1);
        applyOrgScope(qw, "org_id", orgId);
        String whType = normalizeWarehouseTypeFilter(warehouseType);
        if (whType != null) qw.eq("warehouse_type", whType);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("code", k).or().like("name", k).or().like("address", k));
        }
        return Result.ok(warehouseMapper.selectList(qw.orderByAsc("code")));
    }

    @PostMapping("/warehouses")
    public Result<?> createWarehouse(@RequestBody MdWarehouse w) {
        SecurityUtil.requireAccountAdmin();
        normalizeWarehouse(w);
        assertWarehouseCodeUnique(w.getCode(), w.getOrgId(), null);
        assertWarehouseNameUnique(w.getName(), w.getOrgId(), null);
        warehouseMapper.insert(w);
        return Result.ok();
    }

    @PutMapping("/warehouses/{id}")
    public Result<?> updateWarehouse(@PathVariable Long id, @RequestBody MdWarehouse w) {
        SecurityUtil.requireAccountAdmin();
        MdWarehouse existing = warehouseMapper.selectById(id);
        if (existing == null) throw new BizException(4040, "仓库不存在");
        assertOrgWritable(existing.getOrgId(), "无权维护其他账套的仓库");
        if (w.getCode() == null || w.getCode().isBlank()) w.setCode(existing.getCode());
        normalizeWarehouse(w);
        assertWarehouseCodeUnique(w.getCode(), existing.getOrgId(), id);
        assertWarehouseNameUnique(w.getName(), existing.getOrgId(), id);
        w.setId(id);
        // 仓库所属公司不允许通过编辑修改:存货按公司核算(2026-10-15),仓库换公司会让两家公司的成本账都对不上;
        // 也防止账套管理员把仓库挪进自己无权维护的账套。确需调整走数据迁移。
        w.setOrgId(existing.getOrgId());
        warehouseMapper.updateById(w);
        return Result.ok();
    }

    @OpLog(module = "基础资料", action = "删除仓库")
    @DeleteMapping("/warehouses/{id}")
    public Result<?> deleteWarehouse(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        MdWarehouse existing = warehouseMapper.selectById(id);
        if (existing == null) return Result.ok();
        assertOrgWritable(existing.getOrgId(), "无权删除其他账套的仓库");
        assertWarehouseNotReferenced(id);
        // 软删除必须把 deleted 写成本行 id,不能写 1。
        // 唯一索引是 uk_code(code, deleted):回收站里若已躺着一条同编码的记录(deleted 同为 1),
        // 再删第二条同编码的就撞键,报「系统异常」且这条档案永远删不掉——曾有
        // 工程项目编码就是这么卡住的。写成 id 则每条互不相同,
        // 且原编码立刻释放、可以重新启用。供应商/物料早就是这个写法,统一照抄。
        // md_warehouse 没有 updated_at 列,别照抄其他表的写法
        jdbcTemplate.update("""
                UPDATE md_warehouse
                   SET status = 0,
                       deleted = id
                 WHERE id = ? AND deleted = 0
                """, id);
        return Result.ok();
    }

    /* ============ 工程项目 ============ */

    @GetMapping("/projects")
    public Result<List<MdProject>> listProjects(@RequestParam(defaultValue = "false") Boolean includeDisabled,
                                                @RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) Integer status) {
        QueryWrapper<MdProject> qw = new QueryWrapper<>();
        if (status != null) qw.eq("status", status);
        else if (!Boolean.TRUE.equals(includeDisabled)) qw.eq("status", 1);
        applyOrgScope(qw, "org_id", orgId);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("code", k)
                    .or().like("name", k)
                    .or().like("short_name", k)
                    .or().like("mnemonic_code", k));
        }
        if (category != null && !category.isBlank()) qw.eq("category", category.trim());
        return Result.ok(projectMapper.selectList(qw.orderByAsc("category").orderByAsc("code")));
    }

    /**
     * 新增工程项目时的建议编码前缀，如「ABC-」。
     *
     * <p>各公司编码原来是全集团一个号池、谁先占谁得，撞车后一线只能猜。
     * 约定改成「公司代号-自编号」后天然不会撞；代号沿用单据号已经在用的那一套
     * （{@link DocNoService#companyPrefixFor}），一线在单据上天天见，不用另学。</p>
     *
     * <p>只是<b>预填</b>，不强制：存量 1818 条项目都不带前缀，硬校验会把编辑旧项目卡死。</p>
     */
    @GetMapping("/projects/code-prefix")
    public Result<String> projectCodePrefix(@RequestParam(required = false) Long orgId) {
        SecurityUtil.requireAccountAdmin();
        Long target = orgId != null ? orgId : SecurityUtil.orgId();
        if (target != null && !SecurityUtil.isAdmin()) orgScope.assertOrgVisible(target);
        return Result.ok(docNoService.companyPrefixFor(target) + "-");
    }

    @PostMapping("/projects")
    public Result<?> createProject(@RequestBody MdProject p) {
        SecurityUtil.requireAccountAdmin();
        normalizeProject(p);
        assertProjectCodeUnique(p.getCode(), p.getOrgId(), null);
        assertProjectNameUnique(p.getName(), p.getOrgId(), null);
        projectMapper.insert(p);
        return Result.ok();
    }

    @PutMapping("/projects/{id}")
    public Result<?> updateProject(@PathVariable Long id, @RequestBody MdProject p) {
        SecurityUtil.requireAccountAdmin();
        MdProject existing = projectMapper.selectById(id);
        if (existing == null) throw new BizException(4040, "工程项目不存在");
        assertOrgWritable(existing.getOrgId(), "无权维护其他账套的工程项目");
        normalizeProject(p);
        assertProjectCodeUnique(p.getCode(), existing.getOrgId(), id);
        assertProjectNameUnique(p.getName(), existing.getOrgId(), id);
        p.setId(id);
        projectMapper.updateById(p);
        return Result.ok();
    }

    @OpLog(module = "基础资料", action = "删除工程项目")
    @DeleteMapping("/projects/{id}")
    public Result<?> deleteProject(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        MdProject existing = projectMapper.selectById(id);
        if (existing == null) return Result.ok();
        assertOrgWritable(existing.getOrgId(), "无权删除其他账套的工程项目");
        assertProjectNotReferenced(id);
        // 软删除必须把 deleted 写成本行 id,不能写 1。
        // 唯一索引是 uk_code(code, deleted):回收站里若已躺着一条同编码的记录(deleted 同为 1),
        // 再删第二条同编码的就撞键,报「系统异常」且这条档案永远删不掉——曾有
        // 工程项目编码就是这么卡住的。写成 id 则每条互不相同,
        // 且原编码立刻释放、可以重新启用。供应商/物料早就是这个写法,统一照抄。
        jdbcTemplate.update("""
                UPDATE md_project
                   SET status = 0,
                       deleted = id,
                       updated_at = NOW()
                 WHERE id = ? AND deleted = 0
                """, id);
        return Result.ok();
    }

    @PostMapping(value = "/projects/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ProjectMasterImportService.ImportResult> importProjects(@RequestPart("file") MultipartFile file) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(projectImportService.importExcel(file));
    }

    @GetMapping("/projects/import-template")
    public ResponseEntity<byte[]> projectImportTemplate() {
        SecurityUtil.requireAccountAdmin();
        return excelTemplate("工程项目档案导入模板.xlsx", importTemplateService.projectTemplate());
    }

    private void applyMaterialFilters(QueryWrapper<MdMaterial> qw,
                                      String keyword,
                                      String code,
                                      String name,
                                      String spec,
                                      String materialType,
                                      Long catId,
                                      Long orgId,
                                      Integer isShared,
        Boolean includeChildren,
        Boolean includeDisabled,
        boolean pool) {
        if (!Boolean.TRUE.equals(includeDisabled)) qw.eq("status", 1);
        applyMaterialMasterScope(qw, orgId, isShared, pool);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("name", k).or().like("code", k).or().like("spec", k).or().like("material_type", k));
        }
        if (code != null && !code.isBlank()) qw.like("code", code.trim());
        if (name != null && !name.isBlank()) qw.like("name", name.trim());
        if (spec != null && !spec.isBlank()) qw.like("spec", spec.trim());
        if (materialType != null && !materialType.isBlank()) qw.like("material_type", materialType.trim());
        if (catId != null) {
            List<Long> ids = materialCategoryScope(catId, includeChildren);
            if (ids.size() == 1) qw.eq("cat_id", ids.get(0));
            else if (!ids.isEmpty()) qw.in("cat_id", ids);
        }
    }

    private <T> void applySupplierMasterScope(QueryWrapper<T> qw, Long orgId, Integer isShared, boolean pool) {
        applyScopedMasterOrgScope(qw, "org_id", "md_supplier_org_scope", "supplier_id", orgId, isShared, pool);
    }

    /**
     * 单据选供应商口径（2026-09-17 集团池）：纯供应商(is_customer=0)全库可选；
     * 客户记录(is_customer=1)仍按账套三层可见（本账套范围 + 集团共享 + 存量授权），
     * 避免别家客户的联系方式顺着供应商下拉外泄。系统管理员不做过滤。
     */
    private <T> void applySupplierSelectionScope(QueryWrapper<T> qw) {
        // 管理员在顶栏选了某家公司时，客户记录也按那家公司的可见规则走(viewCompanyOrgIds 非空)
        if (SecurityUtil.isAdmin() && orgScope.viewCompanyOrgIds() == null) return;
        List<Long> orgIds = orgScope.visibleOrgIds();
        qw.apply("(is_customer = 0 OR org_id IN (" + idListSql(orgIds) + ") OR is_shared = 1 OR "
                + scopeExistsSql("md_supplier_org_scope", "supplier_id", orgIds) + ")");
    }

    private <T> void applyMaterialMasterScope(QueryWrapper<T> qw, Long orgId, Integer isShared, boolean pool) {
        applyScopedMasterOrgScope(qw, "org_id", null, null, orgId, isShared, pool);
    }

    /**
     * 物料/供应商的「管理列表」口径：默认只看归属公司可见范围内 + 集团共享(is_shared=1)
     * + scopeTable 里存量授权(历史"批量授权给组织"管理入口已下线，但已授权数据继续生效)。
     * pool=1 或系统管理员才看全池——列表页的「查看全部池」开关即靠这个参数。
     * 注意：这是档案管理列表的口径；单据选用口径是集团池，见 applySupplierSelectionScope / applyMaterialFilters。
     * orgId 入参为纯归属组织精确筛选，isShared 入参保留兼容。
     */
    private <T> void applyScopedMasterOrgScope(QueryWrapper<T> qw, String orgColumn,
                                               String scopeTable, String scopeMasterColumn,
                                               Long orgId, Integer isShared, boolean pool) {
        if (!pool && !SecurityUtil.isAdmin()) {
            List<Long> orgIds = orgScope.visibleOrgIds();
            if (orgIds.isEmpty()) {
                qw.and(w -> w.eq("is_shared", 1));
            } else if (scopeTable != null) {
                qw.and(w -> w.in(orgColumn, orgIds)
                        .or().eq("is_shared", 1)
                        .or().apply(scopeExistsSql(scopeTable, scopeMasterColumn, orgIds)));
            } else {
                qw.and(w -> w.in(orgColumn, orgIds).or().eq("is_shared", 1));
            }
        }
        if (orgId != null) qw.eq(orgColumn, orgId);
        if (isShared != null) qw.eq("is_shared", isShared);
    }

    private String scopeExistsSql(String scopeTable, String scopeMasterColumn, List<Long> orgIds) {
        return "EXISTS (SELECT 1 FROM " + scopeTable + " __scope WHERE __scope." + scopeMasterColumn
                + " = id AND __scope.org_id IN (" + idListSql(orgIds) + "))";
    }

    private String idListSql(List<Long> ids) {
        List<String> parts = new ArrayList<>();
        for (Long id : ids) {
            if (id != null) parts.add(String.valueOf(id));
        }
        return parts.isEmpty() ? "NULL" : String.join(",", parts);
    }

    private void attachSupplierScopeOrgs(List<MdSupplier> suppliers) {
        if (suppliers == null || suppliers.isEmpty()) return;
        List<Long> ids = suppliers.stream().map(MdSupplier::getId).toList();
        Map<Long, List<String>> scopeMap = loadScopeOrgNames("md_supplier_org_scope", "supplier_id", ids);
        for (MdSupplier supplier : suppliers) {
            supplier.setScopeOrgs(scopeMap.getOrDefault(supplier.getId(), List.of()));
        }
    }

    private void attachMaterialScopeOrgs(List<MdMaterial> materials) {
        if (materials == null || materials.isEmpty()) return;
        List<Long> ids = materials.stream().map(MdMaterial::getId).toList();
        Map<Long, List<String>> scopeMap = loadScopeOrgNames("md_material_org_scope", "material_id", ids);
        for (MdMaterial material : materials) {
            material.setScopeOrgs(scopeMap.getOrDefault(material.getId(), List.of()));
        }
    }

    private Map<Long, List<String>> loadScopeOrgNames(String scopeTable, String scopeMasterColumn, List<Long> masterIds) {
        if (masterIds == null || masterIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(masterIds.size(), "?"));
        Map<Long, List<String>> scopeMap = new HashMap<>();
        String sql = "SELECT s." + scopeMasterColumn + ", o.name FROM " + scopeTable
                + " s JOIN sys_org o ON o.id = s.org_id WHERE s." + scopeMasterColumn + " IN (" + placeholders + ")";
        org.springframework.jdbc.core.RowCallbackHandler handler =
                rs -> scopeMap.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
        jdbcTemplate.query(sql, handler, masterIds.toArray());
        return scopeMap;
    }

    private Long findSupplierDuplicate(MdSupplier supplier, Long excludeId) {
        Long byCode = findDuplicateByField("md_supplier", "code", cleanText(supplier.getCode()), excludeId);
        if (byCode != null) return byCode;
        return findDuplicateByField("md_supplier", "name", cleanText(supplier.getName()), excludeId);
    }

    private Long findMaterialDuplicate(MdMaterial material, Long excludeId) {
        Long byCode = findDuplicateByField("md_material", "code", cleanText(material.getCode()), excludeId);
        if (byCode != null) return byCode;
        String name = cleanText(material.getName());
        String spec = cleanText(material.getSpec());
        String unit = cleanText(material.getUnit());
        if (name.isBlank() || unit.isBlank()) return null;
        if (excludeId == null) {
            return jdbcTemplate.query("""
                    SELECT id
                    FROM md_material
                    WHERE deleted = 0
                      AND name = ?
                      AND COALESCE(spec, '') = ?
                      AND unit = ?
                    LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, name, spec, unit);
        }
        return jdbcTemplate.query("""
                SELECT id
                FROM md_material
                WHERE deleted = 0
                  AND name = ?
                  AND COALESCE(spec, '') = ?
                  AND unit = ?
                  AND id <> ?
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, name, spec, unit, excludeId);
    }

    private Long findDuplicateByField(String table, String field, String value, Long excludeId) {
        if (value == null || value.isBlank()) return null;
        if (excludeId == null) {
            return jdbcTemplate.query(
                    "SELECT id FROM " + table + " WHERE deleted = 0 AND " + field + " = ? LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    value);
        }
        return jdbcTemplate.query(
                "SELECT id FROM " + table + " WHERE deleted = 0 AND " + field + " = ? AND id <> ? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                value, excludeId);
    }

    private void grantSupplierScope(Long supplierId, Long orgId) {
        if (supplierId == null || orgId == null) return;
        jdbcTemplate.update("INSERT IGNORE INTO md_supplier_org_scope (supplier_id, org_id) VALUES (?, ?)",
                supplierId, orgId);
    }

    private void grantMaterialScope(Long materialId, Long orgId) {
        if (materialId == null || orgId == null) return;
        jdbcTemplate.update("INSERT IGNORE INTO md_material_org_scope (material_id, org_id) VALUES (?, ?)",
                materialId, orgId);
    }

    private String cleanText(String value) {
        return value == null ? "" : value.trim();
    }

    /** 账套内仓库名称唯一：同一账套（账套集）内不允许出现同名仓库。 */
    private void assertWarehouseNameUnique(String name, Long orgId, Long excludeId) {
        String n = cleanText(name);
        if (n.isBlank() || orgId == null) return;
        List<Long> accountOrgs = orgScope.accountSetOrgIds(orgId);
        if (accountOrgs.isEmpty()) accountOrgs = List.of(orgId);
        QueryWrapper<MdWarehouse> qw = new QueryWrapper<>();
        qw.eq("deleted", 0).eq("name", n).in("org_id", accountOrgs);
        if (excludeId != null) qw.ne("id", excludeId);
        if (warehouseMapper.selectCount(qw) > 0) {
            throw new BizException("同一账套内已存在同名仓库：" + n + "，请勿重复创建");
        }
    }

    /** 账套内工程项目名称唯一：同一账套（账套集）内不允许出现同名工程项目。 */
    private void assertProjectNameUnique(String name, Long orgId, Long excludeId) {
        String n = cleanText(name);
        if (n.isBlank() || orgId == null) return;
        List<Long> accountOrgs = orgScope.accountSetOrgIds(orgId);
        if (accountOrgs.isEmpty()) accountOrgs = List.of(orgId);
        QueryWrapper<MdProject> qw = new QueryWrapper<>();
        qw.eq("deleted", 0).eq("name", n).in("org_id", accountOrgs);
        if (excludeId != null) qw.ne("id", excludeId);
        if (projectMapper.selectCount(qw) > 0) {
            throw new BizException("同一账套内已存在同名工程项目：" + n + "，请勿重复创建");
        }
    }

    /**
     * 编码撞车的提示语。
     *
     * <p>md_project / md_warehouse 的唯一索引是 {@code uk_code(code, deleted)}——编码在
     * <b>全集团范围内</b>唯一，不按公司分。占用方常常在别的账套里，本账套的人在自己列表里
     * 怎么翻都找不到重复的，只看到「系统异常，请联系管理员」：曾有公司新增
     * 工程项目编码 12 就是这么连试了 14 次。</p>
     *
     * <p>撞在本账套内就点名是哪一条，方便直接去改；撞在别家账套只说被占用，
     * 不回显其他账套的档案内容。</p>
     */
    private String duplicateCodeMessage(String label, String code, Long ownerOrgId,
                                        String ownerName, Long myOrgId) {
        List<Long> accountOrgs = myOrgId == null ? List.of() : orgScope.accountSetOrgIds(myOrgId);
        boolean sameAccountSet = ownerOrgId != null
                && (ownerOrgId.equals(myOrgId) || accountOrgs.contains(ownerOrgId));
        if (sameAccountSet) {
            return label + " " + code + " 已被「" + cleanText(ownerName) + "」占用，请换一个编码";
        }
        return label + " " + code + " 已被其他公司占用（编码在全集团范围内不能重复），"
                + "请换一个编码，建议带上公司前缀，例如「简称-" + code + "」";
    }

    /** 工程项目编码全局唯一：撞了必须给人话，别让 DuplicateKeyException 穿到兜底变成「系统异常」。 */
    private void assertProjectCodeUnique(String code, Long orgId, Long excludeId) {
        String c = cleanText(code);
        if (c.isBlank()) return;
        QueryWrapper<MdProject> qw = new QueryWrapper<>();
        qw.eq("deleted", 0).eq("code", c);
        if (excludeId != null) qw.ne("id", excludeId);
        MdProject dup = projectMapper.selectOne(qw.last("LIMIT 1"));
        if (dup == null) return;
        throw new BizException(duplicateCodeMessage("工程项目编码", c, dup.getOrgId(), dup.getName(), orgId));
    }

    /** 仓库编码全局唯一，同上。手填编码时才撞得到（留空是自动生成的）。 */
    private void assertWarehouseCodeUnique(String code, Long orgId, Long excludeId) {
        String c = cleanText(code);
        if (c.isBlank()) return;
        QueryWrapper<MdWarehouse> qw = new QueryWrapper<>();
        qw.eq("deleted", 0).eq("code", c);
        if (excludeId != null) qw.ne("id", excludeId);
        MdWarehouse dup = warehouseMapper.selectOne(qw.last("LIMIT 1"));
        if (dup == null) return;
        throw new BizException(duplicateCodeMessage("仓库编码", c, dup.getOrgId(), dup.getName(), orgId));
    }

    private <T> void applyOrgScope(QueryWrapper<T> qw, String orgColumn, Long orgId) {
        // 管理员默认看全部；但在顶栏选了某家公司时，要和其它页面一样收窄到那家(走下面的通用范围)
        if (SecurityUtil.isAdmin() && orgScope.viewCompanyOrgIds() == null) {
            if (orgId != null) qw.eq(orgColumn, orgId);
            return;
        }
        orgScope.applyOrgScope(qw, orgColumn);
        if (orgId != null) qw.eq(orgColumn, orgId);
    }

    private void assertOrgWritable(Long orgId, String message) {
        if (SecurityUtil.isAdmin()) return;
        try {
            orgScope.assertOrgVisible(orgId);
        } catch (BizException e) {
            throw new BizException(4003, message);
        }
    }

    private List<Long> materialCategoryScope(Long catId, Boolean includeChildren) {
        if (!Boolean.TRUE.equals(includeChildren)) return List.of(catId);
        List<Long> ids = new ArrayList<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(catId);
        while (!queue.isEmpty()) {
            Long id = queue.removeFirst();
            if (ids.contains(id)) continue;
            ids.add(id);
            List<Long> children = jdbcTemplate.queryForList(
                    "SELECT id FROM md_material_cat WHERE parent_id = ? AND deleted = 0",
                    Long.class, id);
            queue.addAll(children);
        }
        return ids;
    }

    private void normalizeWarehouse(MdWarehouse w) {
        if (w == null) throw new BizException("仓库信息不能为空");
        if (w.getCode() == null || w.getCode().isBlank()) w.setCode(generateNextWarehouseCode());
        if (w.getCode() == null || w.getCode().isBlank()) throw new BizException("仓库编码生成失败");
        if (w.getName() == null || w.getName().isBlank()) throw new BizException("仓库名称不能为空");
        if (w.getOrgId() == null) throw new BizException("仓库必须选择所属组织账套");
        String orgType = jdbcTemplate.queryForList(
                "SELECT type FROM sys_org WHERE id = ? AND deleted = 0 AND status = 1",
                String.class, w.getOrgId()).stream().findFirst().orElse(null);
        if (orgType == null) throw new BizException("所属组织账套不存在或已停用");
        // 计算审查 U31(2026-10-18):成本按"仓库所属组织"建公司成本池。仓库挂在部门下,同一家公司会分裂成两个池
        // (同一物料两个单价)、同公司内退料被判成跨公司。所以仓库只能挂在公司(或集团)上,部门用仓库名区分。
        if ("DEPT".equalsIgnoreCase(orgType)) {
            throw new BizException("仓库只能归属到公司，不能挂在部门下（同一公司的库存要算在一起）。请选择所属公司，部门可写进仓库名称");
        }
        assertOrgWritable(w.getOrgId(), "只能维护本账套内的仓库");
        w.setCode(w.getCode().trim());
        w.setName(w.getName().trim());
        w.setWarehouseType(normalizeWarehouseTypeForSave(w.getWarehouseType()));
        if (w.getStatus() == null) w.setStatus(1);
    }

    private String normalizeWarehouseTypeFilter(String value) {
        if (value == null || value.isBlank()) return null;
        return normalizeWarehouseType(value);
    }

    private String normalizeWarehouseTypeForSave(String value) {
        if (value == null || value.isBlank()) return "SECONDARY";
        return normalizeWarehouseType(value);
    }

    private String normalizeWarehouseType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase();
        if (!"MAIN".equals(type) && !"SECONDARY".equals(type)) {
            throw new BizException("仓库类型只能是总仓或二级仓");
        }
        return type;
    }

    private String generateNextWarehouseCode() {
        List<String> codes = jdbcTemplate.queryForList("""
                SELECT code
                FROM md_warehouse
                WHERE deleted = 0
                  AND code IS NOT NULL
                  AND code REGEXP '^[0-9]+$'
                """, String.class);
        int max = 0;
        int width = 2;
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            try {
                String text = code.trim();
                max = Math.max(max, Integer.parseInt(text));
                width = Math.max(width, text.length());
            } catch (NumberFormatException ignored) {
                // Historical non-numeric codes are not part of the new warehouse sequence.
            }
        }
        return String.format("%0" + width + "d", max + 1);
    }

    private void normalizeProject(MdProject p) {
        if (p == null) throw new BizException("工程项目信息不能为空");
        if (p.getCode() == null || p.getCode().isBlank()) throw new BizException("工程项目编码不能为空");
        if (p.getName() == null || p.getName().isBlank()) throw new BizException("工程项目名称不能为空");
        if (p.getCategory() == null || p.getCategory().isBlank()) throw new BizException("工程项目类别不能为空");
        if (p.getOrgId() == null) p.setOrgId(defaultOrgId());
        Integer orgCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE id = ? AND deleted = 0 AND status = 1",
                Integer.class, p.getOrgId());
        if (orgCount == null || orgCount == 0) throw new BizException("所属组织不存在或已停用");
        assertOrgWritable(p.getOrgId(), "只能维护本账套内的工程项目");
        p.setCode(p.getCode().trim());
        p.setName(p.getName().trim());
        p.setCategory(p.getCategory().trim());
        if (p.getParentId() == null) p.setParentId(0L);
        if (p.getStatus() == null) p.setStatus(1);
    }

    private Long defaultOrgId() {
        Long currentOrgId = SecurityUtil.orgId();
        if (currentOrgId != null) return currentOrgId;
        Long orgId = jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (orgId == null) throw new BizException("请先维护组织单位");
        return orgId;
    }

    private void normalizeSupplier(MdSupplier s) {
        if (s == null) throw new BizException("供应商信息不能为空");
        if (s.getCode() == null || s.getCode().isBlank()) s.setCode(generateNextSupplierCode());
        if (s.getCode() == null || s.getCode().isBlank()) throw new BizException("供应商编码不能为空");
        if (s.getOrgId() == null) s.setOrgId(defaultMasterOrgId());
        String orgName = orgNameById(s.getOrgId());
        if (orgName == null) throw new BizException("所属组织不存在或已停用");
        assertOrgWritable(s.getOrgId(), "只能维护本账套内的供应商");
        if (s.getName() == null || s.getName().isBlank()) throw new BizException("供应商名称不能为空");
        if (s.getSupplierCategory() == null || s.getSupplierCategory().isBlank()) throw new BizException("供应商基本分类不能为空");
        if (s.getSupplierType() == null || s.getSupplierType().isBlank()) throw new BizException("供应商类型不能为空");
        s.setCode(s.getCode().trim());
        s.setOrgName(orgName);
        s.setName(s.getName().trim());
        s.setSupplierCategory(s.getSupplierCategory().trim());
        s.setSupplierType(s.getSupplierType().trim());
        if (s.getShortName() == null || s.getShortName().isBlank()) s.setShortName(s.getName());
        if (s.getIsCustomer() == null) s.setIsCustomer(0);
        if (s.getIsShared() == null) s.setIsShared(isGroupOrg(s.getOrgId()) ? 1 : 0);
        if (s.getStatus() == null) s.setStatus(1);
    }

    private String generateNextSupplierCode() {
        List<String> codes = jdbcTemplate.queryForList("""
                SELECT code
                FROM md_supplier
                WHERE deleted = 0
                  AND code IS NOT NULL
                  AND code REGEXP '^[A-Za-z]*[0-9]+$'
                """, String.class);
        String bestPrefix = "S";
        int bestWidth = 3;
        long bestNumber = 0;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^([A-Za-z]*)(\\d+)$");
        for (String code : codes) {
            if (code == null) continue;
            java.util.regex.Matcher matcher = pattern.matcher(code.trim());
            if (!matcher.matches()) continue;
            long number;
            try {
                number = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (number > bestNumber) {
                bestNumber = number;
                bestPrefix = matcher.group(1) == null ? "" : matcher.group(1);
                bestWidth = Math.max(3, matcher.group(2).length());
            }
        }
        return bestPrefix + String.format("%0" + bestWidth + "d", bestNumber + 1);
    }

    private void normalizeMaterial(MdMaterial m) {
        if (m == null) throw new BizException("物料信息不能为空");
        if (m.getCode() == null || m.getCode().isBlank()) throw new BizException("物料编码不能为空");
        if (m.getName() == null || m.getName().isBlank()) throw new BizException("物料名称不能为空");
        if (m.getUnit() == null || m.getUnit().isBlank()) throw new BizException("物料单位不能为空");
        if (m.getOrgId() == null) m.setOrgId(defaultMaterialOrgId());
        String orgName = orgNameById(m.getOrgId());
        if (orgName == null) throw new BizException("所属组织不存在或已停用");
        assertOrgWritable(m.getOrgId(), "只能维护本账套内的物料");
        if (m.getCatId() != null) {
            Integer catCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM md_material_cat WHERE id = ? AND deleted = 0 AND status = 1",
                    Integer.class, m.getCatId());
            if (catCount == null || catCount == 0) throw new BizException("物料分类不存在或已停用");
        }
        m.setCode(m.getCode().trim());
        m.setName(m.getName().trim());
        m.setUnit(m.getUnit().trim());
        if (m.getMaterialType() != null) m.setMaterialType(m.getMaterialType().trim());
        if (m.getIsShared() == null) m.setIsShared(isGroupOrg(m.getOrgId()) ? 1 : 0);
        if (m.getStatus() == null) m.setStatus(1);
    }

    private Long defaultMaterialOrgId() {
        return defaultMasterOrgId();
    }

    private Long defaultMasterOrgId() {
        Long currentOrgId = orgScope.currentAccountSetOrgId();
        if (!SecurityUtil.isAdmin() && currentOrgId != null) return currentOrgId;
        // 1) 系统参数显式指定的默认归属组织
        Long configured = jdbcTemplate.query("""
                SELECT CAST(param_value AS UNSIGNED)
                FROM sys_param
                WHERE param_key = 'master.default_material_org'
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (configured != null && configured > 0) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_org WHERE id = ? AND deleted = 0 AND status = 1",
                    Integer.class, configured);
            if (exists != null && exists > 0) return configured;
        }
        // 2) 根组织：type='GROUP' 且 parent_id=0，id 最小的
        Long orgId = jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                  AND type = 'GROUP' AND parent_id = 0
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (orgId != null) return orgId;
        // 3) 回退：任意 GROUP 类型
        orgId = jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                ORDER BY CASE WHEN type = 'GROUP' THEN 0 ELSE 1 END, parent_id, sort, id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (orgId == null) throw new BizException("请先维护组织单位");
        return orgId;
    }

    private boolean isGroupOrg(Long orgId) {
        if (orgId == null) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_org
                WHERE id = ? AND deleted = 0 AND status = 1 AND type = 'GROUP'
                """, Integer.class, orgId);
        return count != null && count > 0;
    }

    private String orgNameById(Long orgId) {
        if (orgId == null) return null;
        return jdbcTemplate.query("""
                SELECT name
                FROM sys_org
                WHERE id = ? AND deleted = 0 AND status = 1
                LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null, orgId);
    }

    private String generateNextMaterialCode(Long catId) {
        if (catId == null) throw new BizException("请选择物料分类后再生成编码");
        MdMaterialCat cat = materialCatMapper.selectOne(new QueryWrapper<MdMaterialCat>()
                .eq("id", catId)
                .eq("status", 1)
                .last("LIMIT 1"));
        if (cat == null) throw new BizException("物料分类不存在或已停用");

        String prefix = materialCodePrefix(cat.getCode());
        String like = prefix + "%";
        String regex = "^" + prefix + "[0-9]+$";
        int suffixStart = prefix.length() + 1;
        Long maxSeq = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(code, ?) AS UNSIGNED)), 0)
                FROM md_material
                WHERE deleted = 0
                  AND code LIKE ?
                  AND code REGEXP ?
                """, Long.class, suffixStart, like, regex);
        Integer maxWidth = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(CHAR_LENGTH(SUBSTRING(code, ?))), 0)
                FROM md_material
                WHERE deleted = 0
                  AND code LIKE ?
                  AND code REGEXP ?
                """, Integer.class, suffixStart, like, regex);
        long next = (maxSeq == null ? 0 : maxSeq) + 1;
        int width = Math.max(4, maxWidth == null ? 0 : maxWidth);
        return prefix + String.format("%0" + width + "d", next);
    }

    private String materialCodePrefix(String categoryCode) {
        String prefix = categoryCode == null ? "" : categoryCode.replaceAll("\\D", "");
        if (prefix.isBlank()) throw new BizException("所选分类编码不含数字，无法自动生成物料编码");
        return prefix.length() > 8 ? prefix.substring(0, 8) : prefix;
    }

    private ResponseEntity<byte[]> excelTemplate(String fileName, byte[] content) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    private void assertWarehouseNotReferenced(Long id) {
        assertWarehouseNotUsedByTransfer(id);
        String[] deletedTables = { "biz_po", "biz_recv", "biz_out_req", "biz_out" };
        for (String table : deletedTables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE warehouse_id = ? AND deleted = 0",
                    Integer.class, id);
            if (count != null && count > 0) {
                throw new BizException("该仓库已有业务单据或库存流水，不能删除");
            }
        }
        String[] stockTables = { "inv_balance", "inv_transaction" };
        for (String table : stockTables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE warehouse_id = ?",
                    Integer.class, id);
            if (count != null && count > 0) {
                throw new BizException("该仓库已有业务单据或库存流水，不能删除");
            }
        }
    }

    private void assertWarehouseNotUsedByTransfer(Long id) {
        if (!tableExists("biz_transfer")) return;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_transfer WHERE deleted = 0 AND (from_warehouse_id = ? OR to_warehouse_id = ?)",
                Integer.class, id, id);
        if (count != null && count > 0) {
            throw new BizException("该仓库已有转库单据，不能删除");
        }
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private void assertProjectNotReferenced(Long id) {
        String[] tables = { "biz_out_req", "biz_out" };
        for (String table : tables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE project_id = ? AND deleted = 0",
                    Integer.class, id);
            if (count != null && count > 0) {
                throw new BizException("该工程项目已有出库业务引用，不能删除");
            }
        }
    }

    /** 批量分配组织请求体 */
    public static class BatchAssignOrgDTO {
        public List<Long> ids;
        public Long orgId;
    }
}
