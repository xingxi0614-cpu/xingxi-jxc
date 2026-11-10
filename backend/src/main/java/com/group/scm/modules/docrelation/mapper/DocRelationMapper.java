package com.group.scm.modules.docrelation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.docrelation.entity.DocRelation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DocRelationMapper extends BaseMapper<DocRelation> {

    @Select("SELECT * FROM doc_relation WHERE source_type = #{type} AND source_no = #{no} ORDER BY id")
    List<DocRelation> findBySource(@Param("type") String type, @Param("no") String no);

    @Select("SELECT * FROM doc_relation WHERE target_type = #{type} AND target_no = #{no} ORDER BY id")
    List<DocRelation> findByTarget(@Param("type") String type, @Param("no") String no);

    @Select("""
        SELECT * FROM doc_relation
        WHERE source_no IN (SELECT source_no FROM doc_relation WHERE source_no = #{no} OR target_no = #{no})
           OR target_no IN (SELECT target_no FROM doc_relation WHERE source_no = #{no} OR target_no = #{no})
           OR source_no = #{no} OR target_no = #{no}
    """)
    List<DocRelation> findRelated(@Param("no") String no);
}
