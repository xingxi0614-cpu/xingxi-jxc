package com.group.scm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常脱敏闸门（纯函数，不需要数据库，任何时候都会跑）。
 *
 * <p>由来：删除用户撞唯一键，操作日志里躺着整段
 * "### Error updating database ... ### SQL: UPDATE sys_user ..."。报错不回显 SQL 是安全红线。</p>
 */
class SafeErrorMessageTest {

    /** 按 MyBatis 真实格式构造的异常 message。 */
    private static final String PROD_MSG = """

            ### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: Duplicate entry 'zhangsan-1' for key 'sys_user.uk_username'
            ### The error may exist in com/group/scm/modules/system/mapper/SysUserMapper.java (best guess)
            ### SQL: UPDATE sys_user SET deleted=1 WHERE id=? AND deleted=0
            """;

    @Test
    @DisplayName("删除用户撞键异常：不回显 SQL/表名/索引名，给出人话")
    void duplicateKeyFromProduction() {
        Exception e = new DuplicateKeyException(PROD_MSG,
                new SQLIntegrityConstraintViolationException("Duplicate entry 'zhangsan-1' for key 'sys_user.uk_username'"));
        String msg = SafeErrorMessage.of(e);
        assertFalse(msg.contains("SQL"), msg);
        assertFalse(msg.contains("sys_user"), msg);
        assertFalse(msg.contains("zhangsan"), msg);
        assertTrue(msg.contains("重复"), msg);
    }

    @Test
    @DisplayName("被包了一层的数据库异常同样脱敏（导入服务 catch Exception 的情形）")
    void wrappedDatabaseError() {
        Exception e = new RuntimeException("wrapper", new DataIntegrityViolationException(PROD_MSG));
        assertFalse(SafeErrorMessage.of(e).contains("SQL"));
    }

    @Test
    @DisplayName("只凭 message 特征（### SQL）也能认出来")
    void mybatisMessageOnly() {
        assertFalse(SafeErrorMessage.of(new IllegalStateException(PROD_MSG)).contains("sys_user"));
    }

    @Test
    @DisplayName("业务提示和非数据库异常原样保留——那是给用户的有用线索")
    void keepUsefulMessages() {
        assertEquals("该组织已有用户，不能删除", SafeErrorMessage.of(new BizException("该组织已有用户，不能删除")));
        assertEquals("文件不是有效的 Excel", SafeErrorMessage.of(new IllegalArgumentException("文件不是有效的 Excel")));
    }
}
