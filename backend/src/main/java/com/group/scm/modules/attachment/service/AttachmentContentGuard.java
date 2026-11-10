package com.group.scm.modules.attachment.service;

import com.group.scm.common.BizException;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 附件内容检查(安全审查 S09,2026-10-18)。
 *
 * <p><b>为什么:</b>以前只看扩展名和大小,改个扩展名的程序、带宏的 Office、加密压缩包、"解压炸弹"都能传上来,
 * 再被财务、仓管下载打开。服务器不会执行附件,风险在下载的人那一侧。</p>
 *
 * <p><b>检查什么(不装杀毒软件,做能确定判断的几件事):</b>
 * <ol>
 *   <li>文件头和扩展名一致:真 PDF 才能叫 .pdf,真图片才能叫 .jpg……改扩展名的直接拒;</li>
 *   <li>Office:不收带宏的(vbaProject / 宏存储),不收加密文档(无法检查内容);</li>
 *   <li>压缩包(含 docx/xlsx,它们本质是 zip):不收加密条目;解压总量超过 {@value #MAX_UNZIPPED_MB}MB
 *       或条目超过 {@value #MAX_ZIP_ENTRIES} 个的拒(防解压炸弹);</li>
 *   <li>PDF:含自动执行脚本(/JavaScript)或启动外部程序(/Launch)的拒——正常发票、合同、扫描件不会有。
 *       压缩在对象流里的脚本查不到,这是这道检查的边界。</li>
 * </ol>
 * 返回服务器按内容认定的 MIME,下载时用它,不再用上传方自己报的。</p>
 */
final class AttachmentContentGuard {

    static final int MAX_UNZIPPED_MB = 200;
    static final int MAX_ZIP_ENTRIES = 2000;
    private static final long MAX_UNZIPPED = MAX_UNZIPPED_MB * 1024L * 1024L;

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"), Map.entry("pdf", "application/pdf"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("zip", "application/zip"));

    private AttachmentContentGuard() {}

    /** 按扩展名给出下载用的 MIME(历史附件也按它,不用上传方报的类型)。 */
    static String mimeOf(String ext) {
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    /** 检查内容,不通过抛 BizException(给用户看的话);通过返回服务器认定的 MIME。 */
    static String check(String ext, byte[] data) {
        switch (ext) {
            case "jpg", "jpeg" -> require(startsWith(data, 0xFF, 0xD8, 0xFF), "JPG 图片");
            case "png" -> require(startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), "PNG 图片");
            case "webp" -> require(startsWith(data, 'R', 'I', 'F', 'F') && data.length > 12
                    && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P', "WEBP 图片");
            case "pdf" -> checkPdf(data);
            case "xls", "doc" -> checkOle(ext, data);
            case "xlsx", "docx" -> checkOoxml(data);
            case "zip" -> checkZip(data, false);
            default -> throw new BizException("不支持的附件类型：" + ext);
        }
        return mimeOf(ext);
    }

    private static void checkPdf(byte[] data) {
        // 规范允许 %PDF- 前有少量垃圾字节,扫描仪/打印驱动偶有;放宽到前 1KB 内出现
        require(indexOf(data, "%PDF-".getBytes(StandardCharsets.US_ASCII), 1024) >= 0, "PDF 文件");
        if (indexOf(data, "/JavaScript".getBytes(StandardCharsets.US_ASCII), data.length) >= 0
                || indexOf(data, "/Launch".getBytes(StandardCharsets.US_ASCII), data.length) >= 0) {
            throw new BizException("这个 PDF 里带有自动运行的脚本或启动程序的指令，出于安全不能上传。"
                    + "请用“打印为 PDF”或扫描件重新生成后再传");
        }
    }

    private static void checkOle(String ext, byte[] data) {
        require(startsWith(data, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), "xls".equals(ext) ? "Excel 97-2003 文件" : "Word 97-2003 文件");
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(data))) {
            DirectoryEntry root = fs.getRoot();
            if (root.hasEntry("EncryptionInfo") || root.hasEntry("EncryptedPackage")) {
                throw new BizException("加密的文档无法检查内容，不能上传。请去掉打开密码后再传");
            }
            if (hasMacros(root)) {
                throw new BizException("这个文档里带有宏（自动运行的程序），出于安全不能上传。请另存为不带宏的格式后再传");
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BizException("文件内容已损坏或不是有效的 Office 文档，不能上传");
        }
    }

    private static boolean hasMacros(DirectoryEntry dir) {
        for (Entry e : dir) {
            String n = e.getName();
            // Excel: _VBA_PROJECT_CUR;Word: Macros;通用 VBA 存储
            if ("_VBA_PROJECT_CUR".equalsIgnoreCase(n) || "Macros".equalsIgnoreCase(n) || "VBA".equalsIgnoreCase(n)) return true;
            if (e instanceof DirectoryEntry d && hasMacros(d)) return true;
        }
        return false;
    }

    private static void checkOoxml(byte[] data) {
        // 加密的 docx/xlsx 实际是 OLE 容器(不是 zip)
        if (startsWith(data, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            throw new BizException("加密的文档无法检查内容，不能上传。请去掉打开密码后再传");
        }
        require(startsWith(data, 'P', 'K', 0x03, 0x04), "Office 文档");
        checkZip(data, true);
    }

    /** 逐条真解压一遍(只计数不留),所以条目里声明的大小骗不了它。 */
    private static void checkZip(byte[] data, boolean ooxml) {
        require(startsWith(data, 'P', 'K', 0x03, 0x04) || startsWith(data, 'P', 'K', 0x05, 0x06), "ZIP 压缩包");
        boolean contentTypes = false;
        long total = 0;
        int entries = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new BizException("压缩包内文件超过 " + MAX_ZIP_ENTRIES + " 个，不能上传");
                String n = e.getName();
                if ("[Content_Types].xml".equals(n)) contentTypes = true;
                if (ooxml && n.toLowerCase().endsWith("vbaproject.bin")) {
                    throw new BizException("这个文档里带有宏（自动运行的程序），出于安全不能上传。请另存为不带宏的格式后再传");
                }
                total += drain(zin, buf, MAX_UNZIPPED - total);
            }
        } catch (BizException ex) {
            throw ex;
        } catch (ZipException ex) {
            String m = ex.getMessage() == null ? "" : ex.getMessage();
            if (m.contains("encrypted")) throw new BizException("加密的压缩包无法检查内容，不能上传。请去掉密码后再传");
            throw new BizException("压缩包已损坏或格式不正确，不能上传");
        } catch (IOException ex) {
            throw new BizException("压缩包已损坏或格式不正确，不能上传");
        }
        if (ooxml && !contentTypes) throw new BizException("文件内容不是有效的 Office 文档（可能改过扩展名），不能上传");
    }

    private static long drain(InputStream in, byte[] buf, long budget) throws IOException {
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > budget) throw new BizException("压缩包解压后超过 " + MAX_UNZIPPED_MB + "MB，不能上传");
        }
        return n;
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new BizException("文件内容与扩展名不符（不是真正的" + what + "，可能改过扩展名），不能上传");
    }

    private static boolean startsWith(byte[] d, int... sig) {
        if (d.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((d[i] & 0xFF) != (sig[i] & 0xFF)) return false;
        return true;
    }

    private static int indexOf(byte[] d, byte[] pat, int limit) {
        int end = Math.min(d.length, limit + pat.length) - pat.length;
        outer:
        for (int i = 0; i <= end; i++) {
            for (int j = 0; j < pat.length; j++) if (d[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }
}
