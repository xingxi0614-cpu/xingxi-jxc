package com.group.scm.modules.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.toolbox.entity.ToolTask;
import com.group.scm.modules.toolbox.mapper.ToolTaskMapper;
import com.group.scm.modules.toolbox.tool.ToolRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 工具箱:接任务 → 排队跑 → 存结果 → 给下载。
 *
 * 【🔴 数据隔离三道锁 —— 本模块的头等大事,改这个文件前先读一遍】
 *  ① 建任务:org_id 由后端从登录态推出,【绝不】接受前端传 —— 否则改个参数就能往别家塞任务。
 *  ② 查列表/查详情:强制 org_id = 我的账套;拿不到账套返回空(fail-closed)。
 *  ③ 下载文件:先查这个任务是不是我公司的,不是就拒。
 *     绝不能靠"任务 id 猜不到"当防线 —— id 是自增的,猜起来毫无难度。
 *     另外文件名只允许在库里登记过的那几个,不接受前端传任意路径(防 ../../ 穿越)。
 *
 * 【并发控制】服务器只有 2 核,还跑着正在记真账的 ERP。
 *  同时最多跑 2 个任务,第 3 个排队 —— 绝不能因为有人生成报告把机器压死、拖累记账。
 *
 * 【数据保留,定】原始上传文件生成完【即删】;结果文件留 90 天。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ToolboxService {

    private final ToolTaskMapper mapper;
    private final OrgScopeService orgScope;
    private final List<ToolRunner> runners;

    /** 权限编码:默认只给财务岗,在「角色与权限」里勾。前端藏入口不是防线,这里才是。 */
    public static final String PERM_USE = "portal.tool.use";

    @Value("${scm.toolbox.path:/data/scm/toolbox}")
    private String basePath;

    /** 结果保留天数(定 90 天) */
    private static final int KEEP_DAYS = 90;

    /**
     * 同时最多跑 2 个 —— 服务器 2 核,还要留给 ERP 记账。
     * 核心 1 个线程，队列满后扩至 2 个，再满则明确返回失败。
     */
    private final ExecutorService pool = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> { Thread t = new Thread(r, "toolbox-worker"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());

    /** 单实例部署：进程中断的任务不能自动重跑，以免重复生成或覆盖结果。 */
    @jakarta.annotation.PostConstruct
    public void recoverInterrupted() {
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ToolTask>()
                .in("status", "PENDING", "RUNNING")
                .set("status", "FAILED").set("error_msg", "服务重启，任务已中断，请重新提交"));
        cleanupFailedInputs();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${scm.toolbox.cleanup-interval-ms:3600000}", initialDelay = 60000)
    public void scheduledCleanup() {
        cleanupFailedInputs();
        int deleted = cleanExpired();
        if (deleted > 0) log.info("工具箱清理过期任务 {} 条", deleted);
    }

    private void cleanupFailedInputs() {
        for (ToolTask task : mapper.selectList(new QueryWrapper<ToolTask>()
                .eq("status", "FAILED").isNotNull("input_path"))) cleanupInput(task);
    }

    private void cleanupInput(ToolTask task) {
        try {
            deleteInput(task);
            mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ToolTask>()
                    .eq("id", task.getId()).set("input_path", null));
        } catch (RuntimeException e) {
            log.warn("工具箱原始文件待重试清理 taskId={}", task.getId(), e);
        }
    }

    // ── 权限 ──────────────────────────────────────────

    public boolean canUse() {
        return SecurityUtil.hasPerm(PERM_USE);
    }

    private void requireUse() {
        if (!canUse()) throw new BizException(4030, "没有使用工具箱的权限,请联系管理员在「角色与权限」里开通");
    }

    /** 我的账套。拿不到就是没挂公司 —— 一律拒,不允许产生无归属的任务 */
    private Long myOrg() {
        Long org = orgScope.currentAccountSetOrgId();
        if (org == null) throw new BizException(4030, "当前账号未挂公司,无法使用工具箱");
        return org;
    }

    // ── 工具清单 ──────────────────────────────────────

    public List<Map<String, Object>> tools() {
        requireUse();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolRunner r : runners) {
            out.add(Map.of("code", r.code(), "name", r.name(), "desc", r.desc(),
                    "needFile", r.needFile(), "instant", r.instant(), "group", r.group()));
        }
        return out;
    }

    // ── 任务 ──────────────────────────────────────────

    /** 我公司的任务列表。第②道锁在这里。 */
    public List<ToolTask> list() {
        requireUse();
        Long org = myOrg();
        List<ToolTask> rows = mapper.selectList(new QueryWrapper<ToolTask>()
                .eq("org_id", org)
                .orderByDesc("id")
                .last("LIMIT 50"));
        // 列表不回传参数正文(可能含敏感填写内容),点开详情再给
        rows.forEach(t -> t.setParams(null));
        return rows;
    }

    /** 单个任务(轮询进度用)。同样按 org_id 过滤。 */
    public ToolTask get(Long id) {
        requireUse();
        ToolTask t = mapper.selectOne(new QueryWrapper<ToolTask>()
                .eq("id", id).eq("org_id", myOrg()).last("LIMIT 1"));
        if (t == null) throw new BizException(4040, "任务不存在");
        return t;
    }

    /**
     * 建任务并排队执行。
     * @param toolCode 工具编码
     * @param title    任务标题
     * @param params   工具参数 JSON(前端表单)
     */
    public ToolTask submit(String toolCode, String title, String params) {
        requireUse();
        ToolRunner runner = runners.stream().filter(r -> r.code().equals(toolCode)).findFirst()
                .orElseThrow(() -> new BizException(4000, "没有这个工具"));

        ToolTask t = new ToolTask();
        t.setOrgId(myOrg());          // ① 第一道锁:归属由后端定,不看前端
        t.setToolCode(toolCode);
        t.setTitle(title == null || title.isBlank() ? runner.name() : title.trim());
        t.setStatus("PENDING");
        t.setParams(params);
        mapper.insert(t);

        final Long taskId = t.getId();
        final Long orgId = t.getOrgId();
        try {
            pool.submit(() -> run(taskId, orgId, runner, params));
        } catch (RejectedExecutionException e) {
            fail(taskId, "排队的任务太多了,请过几分钟再试");
        }
        return t;
    }

    /**
     * 即时型工具:直接算,不建任务、不落文件、不排队。
     * 仍然要过权限;但因为不产生任何数据,不需要账套隔离(算的是用户自己输入的数)。
     */
    public Map<String, Object> runInstant(String toolCode, String params) {
        requireUse();
        ToolRunner runner = runners.stream().filter(r -> r.code().equals(toolCode) && r.instant()).findFirst()
                .orElseThrow(() -> new BizException(4000, "没有这个即时工具"));
        try {
            return runner.runInstant(params == null ? "{}" : params);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("即时工具失败 tool={}", toolCode, e);
            throw new BizException(4000, "计算失败:" + shortReason(e));
        }
    }

    /**
     * 带上传文件的提交。
     * 🔴 文件名【不用】用户上传的原始名 —— 用固定的 input+扩展名,
     *    杜绝 ../ 路径穿越和奇怪字符;原始名对处理逻辑毫无用处。
     */
    public ToolTask submitWithFile(String toolCode, String title, String params,
                                   org.springframework.web.multipart.MultipartFile file) {
        requireUse();
        ToolRunner runner = runners.stream().filter(r -> r.code().equals(toolCode)).findFirst()
                .orElseThrow(() -> new BizException(4000, "没有这个工具"));
        if (file == null || file.isEmpty()) throw new BizException(4000, "请选择要上传的文件");
        if (file.getSize() > 20L * 1024 * 1024) throw new BizException(4000, "文件不能超过 20MB");

        String orig = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = orig.toLowerCase().endsWith(".xls") ? ".xls" : ".xlsx";
        if (!orig.toLowerCase().endsWith(".xls") && !orig.toLowerCase().endsWith(".xlsx")) {
            throw new BizException(4000, "只支持 Excel 文件(.xlsx 或 .xls)");
        }

        ToolTask t = new ToolTask();
        t.setOrgId(myOrg());
        t.setToolCode(toolCode);
        t.setTitle(title == null || title.isBlank() ? runner.name() : title.trim());
        t.setStatus("PENDING");
        t.setParams(params);
        t.setInputPath("input" + ext);
        mapper.insert(t);

        final Long taskId = t.getId();
        final Long orgId = t.getOrgId();
        try {
            Path dir = taskDir(orgId, taskId);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve("input" + ext).toFile());
        } catch (Exception e) {
            fail(taskId, "文件保存失败,请重试");
            return t;
        }
        try {
            pool.submit(() -> run(taskId, orgId, runner, params));
        } catch (RejectedExecutionException e) {
            fail(taskId, "排队的任务太多了,请过几分钟再试");
        }
        return t;
    }

    /** 真正跑工具。任何异常都要落成用户看得懂的失败原因,不能让任务永远卡在运行中。 */
    private void run(Long taskId, Long orgId, ToolRunner runner, String params) {
        long t0 = System.currentTimeMillis();
        try {
            updateStatus(taskId, "RUNNING", null);
            Path dir = taskDir(orgId, taskId);
            Files.createDirectories(dir);

            ToolTask cur = mapper.selectById(taskId);
            Path input = (cur != null && cur.getInputPath() != null) ? dir.resolve(cur.getInputPath()) : null;
            List<ToolRunner.OutFile> outs = runner.run(params, dir, input);

            ToolTask t = mapper.selectById(taskId);
            if (t == null) return;
            t.setStatus("SUCCESS");
            t.setOutputFiles(toJson(outs));
            t.setCostMs((int) (System.currentTimeMillis() - t0));
            // 定:原始文件生成完即删,不在服务器上长期躺着
            deleteInput(t);
            mapper.updateById(t);
            // 🔴 input_path 要单独用 UpdateWrapper 置空:
            //    MyBatis-Plus 默认策略是 NOT_NULL —— setInputPath(null) 会被【直接忽略】,
            //    结果文件删了、库里字段还留着,看起来像"没删"。
            mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ToolTask>()
                    .eq("id", taskId).set("input_path", null));
        } catch (BizException e) {
            fail(taskId, e.getMessage());
        } catch (Exception e) {
            // 不把堆栈/内部路径回显给用户(泄露内部信息是红线);详细的记服务器日志
            log.error("工具任务失败 taskId={} tool={}", taskId, runner.code(), e);
            fail(taskId, "生成失败:" + shortReason(e));
        }
    }

    private String shortReason(Exception e) {
        String m = com.group.scm.common.SafeErrorMessage.of(e);
        if (m == null || m.isBlank()) return "系统内部错误,请联系管理员查看日志";
        // 截断并去掉可能的路径信息
        m = m.replaceAll("/[\\w./-]+", "…");
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private void updateStatus(Long id, String status, String err) {
        ToolTask t = mapper.selectById(id);
        if (t == null) return;
        t.setStatus(status);
        if (err != null) t.setErrorMsg(err.length() > 480 ? err.substring(0, 480) : err);
        mapper.updateById(t);
    }

    private void fail(Long id, String msg) {
        updateStatus(id, "FAILED", msg);
        ToolTask task = mapper.selectById(id);
        if (task != null) cleanupInput(task);
    }

    // ── 下载 ──────────────────────────────────────────

    /**
     * 取结果文件。第③道锁在这里:
     *  · 先按 (id, org_id) 查任务 —— 别家的任务查不出来,自然下不了
     *  · 文件名必须是这个任务【登记过】的,不接受前端传任意名字(防路径穿越)
     */
    public Path download(Long taskId, String fileName) {
        requireUse();
        ToolTask t = get(taskId);       // 已按 org_id 过滤
        if (!"SUCCESS".equals(t.getStatus())) throw new BizException(4000, "任务还没成功,没有可下载的文件");
        String json = t.getOutputFiles() == null ? "" : t.getOutputFiles();
        // 只认登记过的文件名。这一步同时挡掉了 ../ 之类的花样
        if (fileName == null || fileName.isBlank() || !json.contains("\"file\":\"" + fileName + "\"")) {
            throw new BizException(4030, "文件不属于这个任务");
        }
        Path p = taskDir(t.getOrgId(), t.getId()).resolve(fileName).normalize();
        // 双保险:算出来的路径必须还在这个任务自己的目录里
        if (!p.startsWith(taskDir(t.getOrgId(), t.getId()))) throw new BizException(4030, "非法路径");
        if (!Files.exists(p)) throw new BizException(4040, "文件已过期或被清理(结果保留 " + KEEP_DAYS + " 天)");
        return p;
    }

    /** 删任务(连同它的文件)。同样只能删自己公司的。 */
    public void remove(Long id) {
        ToolTask t = get(id);
        if ("PENDING".equals(t.getStatus()) || "RUNNING".equals(t.getStatus()))
            throw new BizException("任务执行中，请完成后再删除");
        deleteDir(taskDir(t.getOrgId(), t.getId()));
        mapper.deleteById(id);
    }

    // ── 私有 ──────────────────────────────────────────

    /** 每个任务一个独立目录:/base/<orgId>/<taskId>/ —— 公司之间目录就是分开的 */
    private Path taskDir(Long orgId, Long taskId) {
        return Paths.get(basePath, String.valueOf(orgId), String.valueOf(taskId)).normalize();
    }

    private void deleteInput(ToolTask t) {
        if (t.getInputPath() == null) return;
        try {
            Path p = taskDir(t.getOrgId(), t.getId()).resolve(t.getInputPath()).normalize();
            if (p.startsWith(taskDir(t.getOrgId(), t.getId()))) Files.deleteIfExists(p);
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private void deleteDir(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (var s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } });
            }
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private String toJson(List<ToolRunner.OutFile> outs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < outs.size(); i++) {
            ToolRunner.OutFile o = outs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(esc(o.name())).append("\",")
              .append("\"file\":\"").append(esc(o.file())).append("\",")
              .append("\"size\":").append(o.size()).append('}');
        }
        return sb.append(']').toString();
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 清理过期结果(90 天)。由定时任务调用。 */
    public int cleanExpired() {
        List<ToolTask> old = mapper.selectList(new QueryWrapper<ToolTask>()
                .lt("created_at", LocalDateTime.now().minusDays(KEEP_DAYS)).in("status", "SUCCESS", "FAILED"));
        int n = 0;
        for (ToolTask t : old) {
            try {
                deleteDir(taskDir(t.getOrgId(), t.getId()));
                mapper.deleteById(t.getId());
                n++;
            } catch (RuntimeException e) {
                log.warn("工具箱过期文件待重试清理 taskId={}", t.getId(), e);
            }
        }
        return n;
    }
}
