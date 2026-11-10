package com.group.scm.modules.attachment.service;

import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.attachment.entity.BizAttachment;
import com.group.scm.modules.attachment.mapper.BizAttachmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 附件存本机目录(纯单测,不需要数据库)。
 * 重点:上传者给的文件名不能决定落盘位置——带 "../" 的名字也只能写进附件目录里。
 */
@DisplayName("附件本机存储:落盘名由服务端生成,不能穿出附件目录")
class AttachmentLocalStoreTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @TempDir Path root;

    private final BizAttachmentMapper mapper = mock(BizAttachmentMapper.class);

    private AttachmentService service() {
        // 配额查询:按方法名统一回"还没传过任何附件"
        JdbcTemplate jdbc = mock(JdbcTemplate.class, inv -> switch (inv.getMethod().getName()) {
            case "queryForMap" -> Map.of("c", 0, "b", 0);
            case "queryForObject" -> 0L;
            default -> null;
        });
        AttachmentService s = new AttachmentService(mapper, mock(OrgScopeService.class), jdbc);
        ReflectionTestUtils.setField(s, "baseDir", root.resolve("att").toString());
        return s;
    }

    @Test
    @DisplayName("原名带 ../ 和斜杠:文件仍落在 附件目录/单据类型/单据id/ 下,原名只进数据库")
    void traversalNameStaysInside() throws Exception {
        service().uploadPublic("LR", 7L, 1L, new MockMultipartFile("file", "../../../evil/x.png", "image/png", PNG));
        ArgumentCaptor<BizAttachment> cap = ArgumentCaptor.forClass(BizAttachment.class);
        verify(mapper).insert(cap.capture());
        BizAttachment a = cap.getValue();
        assertTrue(a.getCosKey().matches("LR/7/[0-9a-f]{32}\\.png"), a.getCosKey());
        assertEquals("../../../evil/x.png", a.getFileName());
        Path stored = root.resolve("att").resolve(a.getCosKey());
        assertArrayEquals(PNG, Files.readAllBytes(stored));
        try (Stream<Path> all = Files.walk(root)) {   // 附件目录外面一个文件都没有,临时文件也没残留
            assertEquals(1, all.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @DisplayName("单据类型不在白名单(比如 ../x)直接拒绝,不落盘")
    void unknownBizTypeRejected() {
        assertThrows(BizException.class, () -> service().uploadPublic("../x", 7L, 1L,
                new MockMultipartFile("file", "a.png", "image/png", PNG)));
        assertFalse(Files.exists(root.resolve("att")));
    }

    @Test
    @DisplayName("数据库里的路径被篡改成 ../ 开头:解析时拒绝,读不到附件目录外的文件")
    void tamperedKeyRejected() {
        AttachmentService s = service();
        assertThrows(BizException.class, () -> ReflectionTestUtils.invokeMethod(s, "resolve", "../../etc/passwd"));
    }
}
