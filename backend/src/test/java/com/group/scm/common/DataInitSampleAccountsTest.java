package com.group.scm.common;

import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全新安装时样例账号的处理(纯单测,不需要数据库)。
 *
 * <p>开源后代码人人可见,写死的密码等于公开密码:样例业务账号默认停用、密码随机;
 * 演示账号 demo 保持启用,密码取环境变量或随机。</p>
 */
@DisplayName("全新安装:样例账号默认停用、密码不写死")
class DataInitSampleAccountsTest {

    /** 种子里的占位哈希前缀(= 全新安装,还没改过密码) */
    private static final String PLACEHOLDER = "$2a$10$7JBUKxhcM5wmWk7rPVTbQOxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    private static final String[] SAMPLES = {"zhangsan", "liujie", "lisi", "sunliu", "wangfang", "zhouwei"};

    private Map<String, SysUser> run(Map<String, String> env) {
        Map<String, SysUser> users = new HashMap<>();
        for (String name : new String[]{"zhangsan", "liujie", "lisi", "sunliu", "wangfang", "zhouwei", "demo"}) {
            SysUser u = new SysUser();
            u.setUsername(name);
            u.setPassword(PLACEHOLDER);
            u.setStatus(1);
            users.put(name, u);
        }
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.findByUsername(anyString())).thenAnswer(inv -> users.get(inv.<String>getArgument(0)));
        new DataInitRunner(mapper, null, null).initSampleAccounts(env::get);
        return users;
    }

    @Test
    @DisplayName("默认:6 个样例业务账号停用,演示账号 demo 保留启用,密码都已不是占位哈希")
    void default_disablesSampleAccounts() {
        Map<String, SysUser> users = run(Map.of());
        for (String name : SAMPLES) {
            assertThat(users.get(name).getStatus()).as(name).isEqualTo(0);
            assertThat(users.get(name).getPassword()).as(name).doesNotStartWith("$2a$10$7JBUKxhcM5wmWk7rPVTbQO");
        }
        assertThat(users.get("demo").getStatus()).isEqualTo(1);
        assertThat(users.get("demo").getPassword()).doesNotStartWith("$2a$10$7JBUKxhcM5wmWk7rPVTbQO");
    }

    @Test
    @DisplayName("SCM_SAMPLE_ACCOUNTS_ENABLED=true(本地测试/E2E):样例账号启用")
    void enabledByEnv() {
        Map<String, SysUser> users = run(Map.of("SCM_SAMPLE_ACCOUNTS_ENABLED", "true", "SCM_SAMPLE_PASSWORD", "test-only-pwd"));
        users.values().forEach(u -> assertThat(u.getStatus()).as(u.getUsername()).isEqualTo(1));
    }

    @Test
    @DisplayName("两次全新安装生成的随机密码不同(不是写死的)")
    void randomPasswordsDiffer() {
        String a = run(Map.of()).get("demo").getPassword();
        String b = run(Map.of()).get("demo").getPassword();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("已改过密码的既有环境(非占位哈希)一律不动")
    void existingAccounts_untouched() {
        SysUser u = new SysUser();
        u.setUsername("zhangsan");
        u.setPassword("$2a$10$someRealHashThatWasChangedAlready");
        u.setStatus(1);
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.findByUsername("zhangsan")).thenReturn(u);
        new DataInitRunner(mapper, null, null).initSampleAccounts(Map.<String, String>of()::get);
        assertThat(u.getStatus()).isEqualTo(1);
        assertThat(u.getPassword()).isEqualTo("$2a$10$someRealHashThatWasChangedAlready");
    }
}
