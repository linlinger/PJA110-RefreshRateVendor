/*
 * Copyright (C) 2026 Oplus Project
 *
 * 用户自定义 per-app 规则 — 让用户决定每个应用的刷新率模式和行为。
 *
 * 配置格式 (app_rules.json):
 * {
 *   "com.miHoYo.Yuanshen": { "mode": "120", "memc": true },
 *   "com.ss.android.ugc.aweme": { "mode": "auto", "memc": true },
 *   "com.tencent.mm": { "mode": "60", "memc": false },
 *   "com.example.game": {
 *       "mode": "90",
 *       "memc": true,
 *       "memc_params": ["258 6 10 99 18 99 255 30", "273 3 1 255 2"]
 *   }
 * }
 *
 * 字段:
 *   mode        "auto" = 跟随智能调度; "60"/"90"/"120" = 该应用前台时锁定
 *               (USER_RULE 优先级 75, 高于 MEMC=70 — 用户拍板优先)
 *   memc        true = 强制开 MEMC; false = 强制关 (即使 iris_config 有配置);
 *               缺省 = 由 iris_config.json 是否含该包名决定
 *   memc_params 可选, 覆盖 iris_config.json 里的参数 (同空格分参格式)
 *
 * 注意: memc=true 时建议 mode 用 auto — MEMC 会自己锁 120Hz;
 *       若 mode 锁 60 + memc=true, 用户锁定优先, 芯片插帧输出可能与面板不匹配。
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class PerAppRuleConfig {
    private static final String TAG = "ORR/PerApp";

    /** 配置查找路径 (优先级从高到低) */
    public static final String[] CONFIG_PATHS = {
            "/data/system/orr/app_rules.json",
            "/data/local/tmp/orr_app_rules.json",                        // adb 调试推送
            "/sdcard/Android/data/com.oplus.refreshrate/files/app_rules.json",
    };

    /** 单个应用的规则 */
    public static final class Rule {
        public final String pkg;
        /** 锁定刷新率, -1 = auto (跟随智能调度) */
        public final float lockRate;
        /** MEMC 覆盖: null = 默认 (看 iris_config), true = 强制开, false = 强制关 */
        public final Boolean memc;
        /** MEMC 参数覆盖 (可 null) */
        public final List<String> memcParams;

        public Rule(String pkg, float lockRate, Boolean memc, List<String> memcParams) {
            this.pkg = pkg;
            this.lockRate = lockRate;
            this.memc = memc;
            this.memcParams = memcParams;
        }
    }

    private final Map<String, Rule> mRules = new HashMap<>();
    private String mLoadedPath;

    /** 按优先级查找并加载第一个存在的配置 (app 切换时调用 → 热加载) */
    public synchronized boolean loadFirstAvailable() {
        for (String p : CONFIG_PATHS) {
            if (new File(p).exists() && load(p)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean load(String path) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            Map<String, Rule> rules = new HashMap<>();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                JSONObject o = root.optJSONObject(pkg);
                if (o == null) {
                    continue;
                }
                float lockRate = -1f;
                String mode = o.optString("mode", "auto").trim();
                if ("60".equals(mode)) {
                    lockRate = 60f;
                } else if ("90".equals(mode)) {
                    lockRate = 90f;
                } else if ("120".equals(mode)) {
                    lockRate = 120f;
                }
                Boolean memc = o.has("memc") ? o.getBoolean("memc") : null;
                List<String> memcParams = null;
                JSONArray arr = o.optJSONArray("memc_params");
                if (arr != null && arr.length() > 0) {
                    memcParams = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "").trim();
                        if (!s.isEmpty()) {
                            memcParams.add(s);
                        }
                    }
                }
                rules.put(pkg, new Rule(pkg, lockRate, memc, memcParams));
            }
            synchronized (this) {
                mRules.clear();
                mRules.putAll(rules);
                mLoadedPath = path;
            }
            Log.i(TAG, "loaded " + path + ": " + rules.size() + " app rules");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "parse " + path + " failed", e);
            return false;
        }
    }

    /** 取某应用规则 (无则 null) */
    public synchronized Rule getRule(String pkg) {
        return pkg == null ? null : mRules.get(pkg);
    }

    /** 全部规则 (GUI 用, 副本) */
    public synchronized Map<String, Rule> getAllRules() {
        return new HashMap<>(mRules);
    }

    /** 增/改一条规则 (GUI 用; 内存态, 持久化请配合 toJson + 外部写盘) */
    public synchronized void setRule(Rule r) {
        if (r != null && r.pkg != null) {
            mRules.put(r.pkg, r);
        }
    }

    /** 删除一条规则 (GUI 用) */
    public synchronized void removeRule(String pkg) {
        if (pkg != null) {
            mRules.remove(pkg);
        }
    }

    /** 序列化为 JSON (与磁盘格式一致; GUI 持久化用) */
    public synchronized String toJson() {
        try {
            JSONObject root = new JSONObject();
            for (Rule r : mRules.values()) {
                JSONObject o = new JSONObject();
                if (r.lockRate >= 119.5f) {
                    o.put("mode", "120");
                } else if (r.lockRate >= 89.5f) {
                    o.put("mode", "90");
                } else if (r.lockRate >= 59.5f) {
                    o.put("mode", "60");
                } else {
                    o.put("mode", "auto");
                }
                if (r.memc != null) {
                    o.put("memc", r.memc);
                }
                if (r.memcParams != null && !r.memcParams.isEmpty()) {
                    o.put("memc_params", new JSONArray(r.memcParams));
                }
                root.put(r.pkg, o);
            }
            return root.toString(2);
        } catch (Exception e) {
            Log.e(TAG, "toJson failed", e);
            return "{}";
        }
    }

    public synchronized String getLoadedPath() {
        return mLoadedPath;
    }
}
