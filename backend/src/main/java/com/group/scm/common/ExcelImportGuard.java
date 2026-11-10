package com.group.scm.common;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.IOUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 所有 Excel 导入的统一入口与资源上限(安全审查 S08,2026-10-18)。
 *
 * <p><b>为什么:</b>导入时 POI 把整本工作簿解析进内存,和 ERP 共用一个进程。一个 20MB 的 xlsx 解压后可能是几百 MB,
 * 几个人同时导、或者有人传了"解压炸弹",就能把整个系统拖慢甚至撑爆,所有公司一起受影响。</p>
 *
 * <p><b>三道限:</b>
 * <ol>
 *   <li>同一时间最多 {@value #MAX_CONCURRENT} 个导入在解析,后到的最多等 {@value #WAIT_SECONDS} 秒,等不到就提示稍后再试;</li>
 *   <li>解压比例 / 单个内部文件大小 / 单个对象字节数设上限(POI 自带的防解压炸弹开关,这里收紧),超了直接拒;</li>
 *   <li>每张表最多 {@value #MAX_ROWS} 行,超了提示拆分。</li>
 * </ol>
 * 正常的物料档案导入是几千行量级,远低于这些上限。</p>
 *
 * <p>用法:{@code try (var opened = ExcelImportGuard.open(file); Workbook wb = opened.workbook()) {...}}
 * ——工作簿先关、名额后还(try 资源倒序关闭)。</p>
 */
public final class ExcelImportGuard {

    static final int MAX_CONCURRENT = 2;
    static final int WAIT_SECONDS = 10;
    public static final int MAX_ROWS = 20000;
    /** 单个内部 XML(一张表)解压后的上限:2 万行的表通常 10MB 以内 */
    static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final Semaphore SLOTS = new Semaphore(MAX_CONCURRENT, true);

    static {
        // 压缩比低于 1%(解压后超过 100 倍)视为解压炸弹;POI 默认也是 0.01,这里显式写明以免被改
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        // 单个二进制对象(如嵌入的图片)上限 100MB
        IOUtils.setByteArrayMaxOverride(100 * 1024 * 1024);
    }

    private ExcelImportGuard() {}

    /** 打开着的工作簿 + 占着的名额。关闭时只还名额,工作簿由调用方 try 资源关闭。 */
    public static final class Opened implements AutoCloseable {
        private final Workbook workbook;
        private boolean released;

        private Opened(Workbook workbook) { this.workbook = workbook; }

        public Workbook workbook() { return workbook; }

        @Override public void close() {
            if (!released) {
                released = true;
                SLOTS.release();
            }
        }
    }

    public static Opened open(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的 Excel 文件");
        try {
            return open(file.getInputStream());
        } catch (BizException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new BizException("读取上传文件失败，请重试");
        }
    }

    /** 工具箱等已落盘的文件也走这里,共用同一份名额和上限。 */
    public static Opened open(InputStream in) {
        acquire();
        Workbook wb = null;
        try (InputStream src = in) {
            wb = WorkbookFactory.create(src);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                if (s.getLastRowNum() + 1 > MAX_ROWS) {
                    throw new BizException("工作表「" + s.getSheetName() + "」超过 " + MAX_ROWS + " 行，请拆分成几个文件分别导入");
                }
            }
            return new Opened(wb);
        } catch (BizException e) {
            closeQuietly(wb);
            SLOTS.release();
            throw e;
        } catch (Exception e) {
            closeQuietly(wb);
            SLOTS.release();
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("Zip bomb") || m.contains("exceeds the max") || m.contains("Tried to allocate")) {
                throw new BizException("文件内容过大或结构异常（解压后体量超出上限），请删掉无关的表和格式后重试，或拆分导入");
            }
            throw new BizException("无法读取 Excel：请确认是 .xlsx / .xls 文件且没有加密");
        }
    }

    private static void acquire() {
        try {
            if (!SLOTS.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new BizException("系统正在处理其他导入，请稍等一分钟再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("导入被中断，请重试");
        }
    }

    private static void closeQuietly(Workbook wb) {
        if (wb == null) return;
        try { wb.close(); } catch (Exception ignored) { }
    }
}
