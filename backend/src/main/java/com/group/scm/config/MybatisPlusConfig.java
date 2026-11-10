package com.group.scm.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {

    /** 分页 + 乐观锁插件 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor i = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // Bug #2 修复：限制单页最大条数，size 超过 500 时自动收敛到 500，
        // 避免 pageSize=99999 这类请求一次性拉取全表造成内存溢出 / 超时攻击（DoS）。
        pagination.setMaxLimit(500L);
        i.addInnerInterceptor(pagination);
        i.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return i;
    }

    /** 自动填充：created_at / updated_at / created_by / updated_by */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject mo) {
                strictInsertFill(mo, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(mo, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                Long uid = currentUserId();
                if (uid != null) {
                    strictInsertFill(mo, "createdBy", Long.class, uid);
                    strictInsertFill(mo, "updatedBy", Long.class, uid);
                }
            }

            @Override
            public void updateFill(MetaObject mo) {
                strictUpdateFill(mo, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                Long uid = currentUserId();
                if (uid != null) {
                    strictUpdateFill(mo, "updatedBy", Long.class, uid);
                }
            }

            private Long currentUserId() {
                try {
                    return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }
}
