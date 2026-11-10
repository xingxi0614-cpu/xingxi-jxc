package com.group.scm.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 把异常转成"能给人看"的一句话:数据库类异常一律不回显原文。
 *
 * <p>为什么要有它:MyBatis 的异常 message 带 "### Error updating database / ### SQL: ..."
 * 整段 SQL、表名、索引名。安全红线是"报错不回显 SQL/内部结构"。
 * 2026-09-30 删除用户撞唯一键,SQL 原文进了操作日志页;各 Excel 导入的
 * {@code "xxx导入失败：" + e.getMessage()} 也会把它直接弹给一线。</p>
 *
 * <p>业务异常(BizException)和非数据库异常(如 Excel 格式读不了)原样返回——那是给用户的有用线索。</p>
 */
@Slf4j
public final class SafeErrorMessage {

    private SafeErrorMessage() {}

    /** 纯转换,不记日志。调用方自己已经把完整堆栈记进服务器日志时用这个。 */
    public static String of(Throwable e) {
        if (e == null) return "";
        if (e instanceof BizException) return e.getMessage();
        if (isDatabaseError(e)) {
            return isDuplicateKey(e)
                    ? "数据与已有记录重复（唯一性校验未通过），详情已记入服务器日志"
                    : "数据库操作失败，详情已记入服务器日志";
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? "系统内部错误，请联系管理员查看日志" : m;
    }

    /** 转换并在脱敏时把完整堆栈记进服务器日志——否则原始原因就丢了(不许静默吞掉)。 */
    public static String ofLogged(Throwable e, String context) {
        if (e != null && !(e instanceof BizException) && isDatabaseError(e)) {
            log.error("{}：数据库异常（已对用户脱敏）", context, e);
        }
        return of(e);
    }

    static boolean isDatabaseError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DataAccessException || t instanceof SQLException) return true;
            // MyBatis PersistenceException 不在 classpath 强依赖里判断,按 message 特征兜底
            String m = t.getMessage();
            if (m != null && (m.contains("### Error") || m.contains("### SQL"))) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException || t instanceof SQLIntegrityConstraintViolationException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
