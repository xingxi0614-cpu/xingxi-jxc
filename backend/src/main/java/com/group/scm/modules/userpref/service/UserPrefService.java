package com.group.scm.modules.userpref.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.userpref.entity.SysUserPref;
import com.group.scm.modules.userpref.mapper.SysUserPrefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户个人偏好:收藏页面、查询常用方案等。
 *
 * 【为什么存后端,不存浏览器】存 localStorage 换台电脑就没了、
 * 清缓存也没了,管理员还看不见。改成跟着账号走,在哪登录都是同一套。
 *
 * 【安全口径】严格私有:
 *   ① user_id 一律从登录态取(SecurityUtil.uid()),【绝不】接受前端传 userId
 *      ——否则改个参数就能读写别人的桌面配置。
 *   ② pref_key 走白名单,不让前端往这张表里塞任意键(它是通用 KV,不设防就成了
 *      人人可写的公共存储)。
 *   ③ value 限长,避免有人往里灌大字符串把表撑爆。
 */
@Service
@RequiredArgsConstructor
public class UserPrefService {

    private final SysUserPrefMapper mapper;

    /** 允许的键。新增偏好项时在这里登记,前端传别的一律拒。 */
    private static final Set<String> KEYS = Set.of(
            "fav.pages"           // 收藏的功能页(页签上点 ☆ 收的)
    );

    /**
     * 查询面板「常用方案」(2026-10-20 用户立项):每个列表页一条,键 = "query.presets:" + 页面路径。
     * 按页分开存:30 来个页面×每页最多 10 个方案,合成一条会超过单条上限。
     * 路径只许小写字母/数字/斜杠/短横线、总长不超过列宽 48;每人最多 100 条这类键,防止被当成任意存储灌满。
     */
    private static final String PRESET_PREFIX = "query.presets:";
    private static final java.util.regex.Pattern PRESET_KEY = java.util.regex.Pattern.compile("^query\\.presets:/[a-z0-9/-]{1,33}$");
    private static final int MAX_PRESET_KEYS = 100;

    private static boolean allowed(String key) {
        return key != null && (KEYS.contains(key) || PRESET_KEY.matcher(key).matches());
    }

    /** 单条 value 上限。看板布局撑死几百字节,给 20KB 足够宽松又不至于被灌爆。 */
    private static final int MAX_VALUE_LEN = 20_000;

    private Long me() {
        Long uid = SecurityUtil.uid();
        if (uid == null) throw new BizException(4001, "请登录后操作");
        return uid;
    }

    /** 一次把我的所有偏好取回来(前端进工作台只调这一次,不用为每个键各发一个请求)。 */
    public Map<String, String> myAll() {
        List<SysUserPref> rows = mapper.selectList(
                new QueryWrapper<SysUserPref>().eq("user_id", me()));
        Map<String, String> out = new HashMap<>();
        for (SysUserPref r : rows) {
            // 白名单外的历史脏数据不回传,免得前端拿到不认识的键
            if (allowed(r.getPrefKey())) out.put(r.getPrefKey(), r.getPrefValue());
        }
        return out;
    }

    /** 写一条偏好(upsert)。key 必须在白名单里。 */
    public void put(String key, String value) {
        if (!allowed(key)) throw new BizException(4000, "不支持的偏好项");
        if (value != null && value.length() > MAX_VALUE_LEN) {
            throw new BizException(4000, "偏好内容过长");
        }
        Long uid = me();
        SysUserPref exist = mapper.selectOne(new QueryWrapper<SysUserPref>()
                .eq("user_id", uid).eq("pref_key", key).last("LIMIT 1"));
        if (exist == null) {
            if (key.startsWith(PRESET_PREFIX) && mapper.selectCount(new QueryWrapper<SysUserPref>()
                    .eq("user_id", uid).likeRight("pref_key", PRESET_PREFIX)) >= MAX_PRESET_KEYS) {
                throw new BizException(4000, "常用查询方案太多了，请先删掉一些不用的");
            }
            SysUserPref n = new SysUserPref();
            n.setUserId(uid);
            n.setPrefKey(key);
            n.setPrefValue(value);
            mapper.insert(n);
        } else {
            exist.setPrefValue(value);
            mapper.updateById(exist);
        }
    }

    /** 删一条(「恢复默认布局」用:删掉这条,前端就回到角色默认方案)。 */
    public void remove(String key) {
        if (!allowed(key)) throw new BizException(4000, "不支持的偏好项");
        mapper.delete(new QueryWrapper<SysUserPref>()
                .eq("user_id", me()).eq("pref_key", key));
    }
}
