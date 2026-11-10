package com.group.scm.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.Serializable;

/**
 * 单据头在写事务中始终当前读并锁行，直到头/明细/审批一起提交。
 * 普通详情查询不加锁。所有写入口必须有事务，不能只保护 submit 而遗漏 edit/delete。
 */
public interface DocumentMapper<T> extends BaseMapper<T> {
    @Override
    default T selectById(Serializable id) {
        QueryWrapper<T> query = new QueryWrapper<T>().eq("id", id);
        if (TransactionSynchronizationManager.isActualTransactionActive()) query.last("FOR UPDATE");
        return selectOne(query);
    }
}
