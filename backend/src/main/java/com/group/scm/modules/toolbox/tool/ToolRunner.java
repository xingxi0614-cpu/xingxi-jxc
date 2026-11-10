package com.group.scm.modules.toolbox.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一个工具。
 *
 * 【怎么加新工具】实现这个接口 + 打上 @Component 就行,不用改 Service、不用改前端配置
 * —— 工具清单是从 Spring 容器里收集的(ToolboxService 注入 List<ToolRunner>)。
 *
 * 【两种工具】
 *  · 即时型(instant=true):输入 → 立刻算出结果显示在页面上。不建任务、不生成文件、不排队。
 *    适合金额大写、价税分离这种"填个数就要答案"的,做成后台任务再下载文件是荒谬的。
 *  · 生成型(默认):填表/传文件 → 后台生成 → 下载。适合出报告、出台账。
 */
public interface ToolRunner {

    String code();
    String name();
    /** 一句话说明(给非技术同事看的大白话) */
    String desc();

    /** true = 即时型:输入就出结果,不生成文件 */
    default boolean instant() { return false; }

    /** true = 要上传文件(生成型才有意义) */
    default boolean needFile() { return false; }

    /** 分组,前端按它把工具归类显示 */
    default String group() { return "常用"; }

    /** 即时型的算法。返回什么前端就显示什么(key 是显示名) */
    default Map<String, Object> runInstant(String paramsJson) throws Exception {
        throw new UnsupportedOperationException("本工具不是即时型");
    }

    /**
     * 生成型的算法(不带上传文件)。
     * @param outDir 结果写到这个目录(已建好,本任务专属)
     */
    default List<OutFile> run(String paramsJson, Path outDir) throws Exception {
        throw new UnsupportedOperationException("本工具不是生成型");
    }

    /**
     * 生成型的算法(带上传文件)。默认转给不带文件的那个,
     * 所以老工具不用改;需要文件的工具重写这个方法。
     * @param inputFile 用户上传的文件;没传就是 null
     */
    default List<OutFile> run(String paramsJson, Path outDir, Path inputFile) throws Exception {
        return run(paramsJson, outDir);
    }

    /** name=给人看的名字;file=磁盘上的文件名(下载时按它校验);size=字节 */
    record OutFile(String name, String file, long size) { }
}
