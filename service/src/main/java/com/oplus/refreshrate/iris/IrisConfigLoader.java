/*
 * Copyright (C) 2026 Oplus Project
 *
 * iris_helper 兼容配置加载器 — 社区 iris_config.json 直接可用。
 *
 * 格式 (与 iris_helper 的 iris_config.json 完全一致):
 * {
 *   "off": ["258 1 0", "267 2 3 0", "273 1 0"],
 *   "com.miHoYo.Yuanshen": ["258 6 10 -1 18 -1 3 0", "273 3 1 3 2"],
 *   "com.ss.android.ugc.aweme": ["258 3 10 0 1", "273 3 1 255 2"]
 * }
 *   - key = 包名 ("off" 保留字), value = 该场景下发的命令列表 (空格分参)
 *   - "off" = 清场 (进入序列第一步) 与退出时下发
 *
 * 常用 type (与 ColorOS multimedia_pixelworks_* XML 同源):
 *   258 = MEMC 插帧, 267 = SDR2HDR, 273 = 超分, 56 = 芯片模式 (0=PT, 1=ABYP)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.iris;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class IrisConfigLoader {
    private static final String TAG = "ORR/IrisCfg";

    /** 默认清场命令 (iris_helper 默认 off, 对齐 ColorOS 258-0/267-3-0/273-0 语义) */
    private static final String[] DEFAULT_OFF = {"258 1 0", "267 2 3 0", "273 1 0"};

    /** 配置查找路径 (优先级从高到低; /data/system 为 vendor 化后的正式位置) */
    public static final String[] CONFIG_PATHS = {
            "/data/system/orr/iris_config.json",
            "/data/local/tmp/orr_iris_config.json",                        // adb 调试推送
            "/sdcard/Android/data/com.oplus.refreshrate/files/iris_config.json",
    };

    private final Map<String, List<String>> mAppCommands = new HashMap<>();
    private List<String> mOffCommands = new ArrayList<>(Arrays.asList(DEFAULT_OFF));
    private String mLoadedPath;

    /**
     * 按优先级查找并加载第一个存在的配置。
     * app 版在每次进入 MEMC 前都会重新调用, adb 推完配置立即生效, 免重启。
     */
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
            Map<String, List<String>> apps = new HashMap<>();
            List<String> off = new ArrayList<>();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONArray arr = root.optJSONArray(k);
                if (arr == null) {
                    continue;
                }
                List<String> cmds = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) {
                        cmds.add(s);
                    }
                }
                if ("off".equals(k)) {
                    off = cmds;
                } else if (!cmds.isEmpty()) {
                    apps.put(k, cmds);
                }
            }
            synchronized (this) {
                mAppCommands.clear();
                mAppCommands.putAll(apps);
                mOffCommands = off.isEmpty()
                        ? new ArrayList<>(Arrays.asList(DEFAULT_OFF)) : off;
                mLoadedPath = path;
            }
            Log.i(TAG, "loaded " + path + ": " + apps.size() + " apps, off=" + mOffCommands.size());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "parse " + path + " failed", e);
            return false;
        }
    }

    /** 某包名是否有 MEMC 配置 */
    public synchronized boolean hasPackage(String pkg) {
        return pkg != null && mAppCommands.containsKey(pkg);
    }

    /** 取某包名的命令列表 (无则 null) */
    public synchronized List<String> getCommandsFor(String pkg) {
        if (pkg == null) {
            return null;
        }
        List<String> c = mAppCommands.get(pkg);
        return c == null ? null : new ArrayList<>(c);
    }

    /** 清场命令 (进入第一步 / 退出时下发) */
    public synchronized List<String> getOffCommands() {
        return new ArrayList<>(mOffCommands);
    }

    public synchronized String getLoadedPath() {
        return mLoadedPath;
    }
}
