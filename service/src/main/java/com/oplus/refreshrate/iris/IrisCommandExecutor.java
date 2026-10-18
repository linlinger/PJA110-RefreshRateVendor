/*
 * Copyright (C) 2026 Oplus Project
 *
 * IRIS 命令执行器 — 封装 /odm/bin/irisConfig 调用 (PixelWorks IRIS 独显)。
 *
 * 通道说明 (2026-10-18 实机/反编译确认):
 *   - ColorOS 框架侧: OplusIrisConfigHelper → com.pixelworks.hardware.IrisHal
 *     → libpwirisIoctlWrapper.so → DRM render node 的 iris ioctl
 *   - 等价 CLI: /odm/bin/irisConfig <type> <params...> (空格分参,
 *     与 iris_helper lib.sh 的调用方式一致)
 *   - 命令格式: type 开头, 如 "258 6 10 99 18 99 255 30" (MEMC 插帧)、
 *     "56 1" (芯片模式: 0=PT 工作, 1=ABYP 旁路)
 *
 * app 版策略: 先尝试直接 exec (vendor 化后 + sepolicy 的最终形态),
 * 失败回退 `su -c` (KSU 环境)。
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.iris;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class IrisCommandExecutor {
    private static final String TAG = "ORR/IrisExec";

    public static final String BIN_PATH = "/odm/bin/irisConfig";

    private IrisCommandExecutor() {
    }

    /** irisConfig 二进制是否存在 (不存在则整个 MEMC 特性不可用) */
    public static boolean isAvailable() {
        return new File(BIN_PATH).exists();
    }

    /**
     * 执行一条 IRIS 命令。
     *
     * @param cmd 空格分参命令, 如 "258 6 10 99 18 99 255 30" 或 "56 1"
     * @return true = 执行成功 (exit 0)
     */
    public static boolean exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return true;
        }
        String trimmed = cmd.trim();
        int code = run(buildDirectArgv(trimmed));
        if (code != 0) {
            code = run(buildSuArgv(trimmed));
        }
        if (code != 0) {
            Log.w(TAG, "exec failed (" + code + "): " + trimmed);
        }
        return code == 0;
    }

    /** 直接 exec (vendor 化后的目标形态) */
    private static List<String> buildDirectArgv(String cmd) {
        List<String> argv = new ArrayList<>();
        argv.add(BIN_PATH);
        for (String t : cmd.split("\\s+")) {
            argv.add(t);
        }
        return argv;
    }

    /** su -c 回退 (app 版在 KSU 环境下可用) */
    private static List<String> buildSuArgv(String cmd) {
        List<String> argv = new ArrayList<>();
        argv.add("su");
        argv.add("-c");
        StringBuilder sb = new StringBuilder(BIN_PATH);
        for (String t : cmd.split("\\s+")) {
            sb.append(' ').append(t);
        }
        argv.add(sb.toString());
        return argv;
    }

    private static int run(List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 消费输出避免管道阻塞
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            while (r.readLine() != null) {
                // drain
            }
            r.close();
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }
}
