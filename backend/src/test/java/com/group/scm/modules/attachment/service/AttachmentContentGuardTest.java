package com.group.scm.modules.attachment.service;

import com.group.scm.common.BizException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 安全审查 S09(2026-10-18):附件内容检查。每条都是"修前能传上去"的文件。 */
class AttachmentContentGuardTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    private static String msg(String ext, byte[] data) {
        return assertThrows(BizException.class, () -> AttachmentContentGuard.check(ext, data)).getMessage();
    }

    private static byte[] zip(String[] names, byte[][] bodies) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                z.write(bodies[i]);
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test void acceptsGenuineFiles() throws Exception {
        assertEquals("image/png", AttachmentContentGuard.check("png", PNG));
        assertEquals("application/pdf", AttachmentContentGuard.check("pdf", PDF));
        assertEquals("application/zip", AttachmentContentGuard.check("zip", zip(new String[]{"a.txt"}, new byte[][]{"hi".getBytes()})));
        byte[] docx = zip(new String[]{"[Content_Types].xml", "word/document.xml"}, new byte[][]{"<Types/>".getBytes(), "<w/>".getBytes()});
        assertTrue(AttachmentContentGuard.check("docx", docx).contains("wordprocessingml"));
    }

    @Test void rejectsRenamedFile() {
        assertTrue(msg("jpg", PDF).contains("扩展名不符"));
        assertTrue(msg("pdf", PNG).contains("扩展名不符"));
        assertTrue(msg("xlsx", "MZ\u0090\u0000 fake exe".getBytes(StandardCharsets.ISO_8859_1)).contains("扩展名不符"));
    }

    @Test void rejectsPdfWithScriptOrLaunch() {
        assertTrue(msg("pdf", "%PDF-1.7\n<< /OpenAction << /S /JavaScript /JS (app.alert(1)) >> >>".getBytes()).contains("脚本"));
        assertTrue(msg("pdf", "%PDF-1.7\n<< /S /Launch /F (cmd.exe) >>".getBytes()).contains("脚本"));
    }

    @Test void rejectsOfficeWithMacros() throws Exception {
        byte[] xlsm = zip(new String[]{"[Content_Types].xml", "xl/vbaProject.bin"}, new byte[][]{"<Types/>".getBytes(), new byte[]{1, 2}});
        assertTrue(msg("xlsx", xlsm).contains("宏"));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.getRoot().createDocument("Workbook", new ByteArrayInputStream(new byte[]{0}));
            fs.getRoot().createDirectory("_VBA_PROJECT_CUR").createDocument("PROJECT", new ByteArrayInputStream(new byte[]{1}));
            fs.writeFilesystem(bos);
        }
        assertTrue(msg("xls", bos.toByteArray()).contains("宏"));
    }

    @Test void rejectsEncryptedOfficeAndZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.getRoot().createDocument("EncryptionInfo", new ByteArrayInputStream(new byte[]{0}));
            fs.getRoot().createDocument("EncryptedPackage", new ByteArrayInputStream(new byte[]{0}));
            fs.writeFilesystem(bos);
        }
        assertTrue(msg("docx", bos.toByteArray()).contains("加密"));
        byte[] z = zip(new String[]{"secret.txt"}, new byte[][]{"x".getBytes()});
        z[6] |= 0x01;   // 本地文件头"通用标志位"第 0 位 = 加密
        assertTrue(msg("zip", z).contains("加密"));
    }

    @Test void rejectsZipBomb() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("zeros.bin"));
            byte[] mb = new byte[1024 * 1024];
            for (int i = 0; i <= AttachmentContentGuard.MAX_UNZIPPED_MB; i++) z.write(mb);
            z.closeEntry();
        }
        assertTrue(bos.size() < 2 * 1024 * 1024, "压缩后应很小");
        assertTrue(msg("zip", bos.toByteArray()).contains("解压后超过"));
    }
}
