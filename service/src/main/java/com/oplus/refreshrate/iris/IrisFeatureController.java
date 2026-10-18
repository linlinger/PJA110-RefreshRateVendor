/*
 * Copyright (C) 2026 Oplus Project
 *
 * IRIS 特性控制器 — MEMC 插帧模式状态机 (app 版, 后续稳定后转 vendor HAL)。
 *
 * 行为对齐 ColorOS OplusFeatureMEMC 的进/出时序 (净室重写):
 *   进入: 清场(off) → 56-1 回 ABYP → 等 300ms → 面板锁 120Hz (MEMC 投票)
 *         → 等 400ms → 56-0 切 PT → 等 200ms → 写应用参数 (258/273/...)
 *   退出: 清功能(off) → 解锁刷新率 (清 MEMC 投票重算) → 56-1 回 ABYP
 *
 * 关键设计:
 *   - type 56 = 芯片模式开关 (56-0 = PT 工作, 56-1 = ABYP 旁路)。
 *     ABYP 是本机防冻结的安全态, 一切异常路径必须兜底回 56-1。
 *   - 先锁面板再切 PT, 中间硬等 300/400/200ms — 这是 ColorOS 不出
 *     切换风暴的原因, 时序参数不要随意改小。
 *   - 代数计数器 (generation): 新的进入/退出请求会作废进行中的旧序列。
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.iris;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.oplus.refreshrate.core.PerAppRuleConfig;

import java.util.List;

public class IrisFeatureController {
    private static final String TAG = "ORR/Iris";

    // 芯片模式命令 (type 56: 0 = PT 工作模式, 1 = ABYP 旁路)
    private static final String CMD_PT = "56 0";
    private static final String CMD_ABYP = "56 1";

    // ColorOS 时序参数 (实测/反编译, 勿随意改小)
    private static final long DELAY_ABYP_SETTLE_MS = 300;   // 56-1 后等芯片稳定
    private static final long DELAY_PANEL_LOCK_MS = 400;    // 锁帧后等面板实际切到位
    private static final long DELAY_PT_SETTLE_MS = 200;     // 56-0 后等 PT 管线就绪

    // 状态
    private static final int STATE_IDLE = 0;      // ABYP 安全态
    private static final int STATE_ENTERING = 1;
    private static final int STATE_ACTIVE = 2;    // MEMC 工作中 (PT)
    private static final int STATE_EXITING = 3;

    /** 回调 (都在主线程执行, 与 RefreshRateCore 的投票线程一致) */
    public interface Callback {
        /** MEMC 进入: 投 Priority.MEMC=70 票锁面板刷新率 (对齐 ColorOS WM 投票优先级 70) */
        void lockPanelForMemc();

        /** MEMC 退出: 清 MEMC 投票并重算 (对齐 ColorOS resetToAuto) */
        void unlockPanel();
    }

    private final Context mContext;
    private final Handler mHandler;          // 专属工作线程 (序列时序在此推进)
    private final Handler mMainHandler;
    private final IrisConfigLoader mConfig;
    private final Callback mCallback;

    private int mState = STATE_IDLE;
    private boolean mEnabled = false;
    private boolean mAvailable = false;
    private String mActivePackage;
    private int mGeneration = 0;

    public IrisFeatureController(Context context, IrisConfigLoader config, Callback cb) {
        mContext = context.getApplicationContext();
        mConfig = config;
        mCallback = cb;
        mAvailable = IrisCommandExecutor.isAvailable();
        if (!mAvailable) {
            Log.w(TAG, "irisConfig not found at " + IrisCommandExecutor.BIN_PATH
                    + ", MEMC feature disabled");
        }
        HandlerThread t = new HandlerThread("ORR-Iris");
        t.start();
        mHandler = new Handler(t.getLooper());
        mMainHandler = new Handler(Looper.getMainLooper());
        // 息屏 → 立即退出 (安全兜底, 对齐 ColorOS 息屏清场)
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, f);
    }

    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        Log.i(TAG, "enabled=" + enabled + (mAvailable ? "" : " (irisConfig missing!)"));
        if (!enabled) {
            requestExit();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public boolean isAvailable() {
        return mAvailable;
    }

    public String getActivePackage() {
        return mActivePackage;
    }

    public int getState() {
        return mState;
    }

    /**
     * 前台应用变化 (由 SceneMonitor 转发, 主线程)。
     *
     * @param rule 用户 per-app 规则 (可为 null): memc=true/false 强制开关,
     *             memc_params 覆盖 iris_config 参数
     */
    public void onForegroundApp(final String pkg, final PerAppRuleConfig.Rule rule) {
        if (!mEnabled || !mAvailable) {
            return;
        }
        // app 版: 每次进入前重载配置, adb 推完立即生效
        mConfig.loadFirstAvailable();

        // 决定 MEMC 意图与参数:
        //   规则强制关 → 不进 MEMC (即使 iris_config 配了)
        //   规则强制开 → 即使 iris_config 没配也进 (参数用规则/iris_config 的)
        //   都没有     → 看 iris_config 是否含该包名
        boolean ruleForceOff = rule != null && Boolean.FALSE.equals(rule.memc);
        List<String> params = (rule != null && rule.memcParams != null
                && !rule.memcParams.isEmpty())
                ? rule.memcParams : mConfig.getCommandsFor(pkg);
        boolean configured = params != null && !params.isEmpty();
        boolean wantMemc = !ruleForceOff
                && (configured || (rule != null && Boolean.TRUE.equals(rule.memc)));

        if (!wantMemc) {
            if (mState != STATE_IDLE) {
                Log.i(TAG, "no MEMC for " + pkg + " → exit");
                requestExit();
            }
            return;
        }
        if (pkg.equals(mActivePackage) && mState == STATE_ACTIVE) {
            return;  // 已在 MEMC 中
        }
        requestEnter(pkg, params);
    }

    // ------------------------------------------------------------------
    // 进入序列 (ColorOS 时序)
    // ------------------------------------------------------------------

    private void requestEnter(final String pkg, final List<String> params) {
        final int gen = ++mGeneration;
        mState = STATE_ENTERING;
        mActivePackage = pkg;
        Log.i(TAG, "enter MEMC: " + pkg + " gen=" + gen
                + " params=" + (params == null ? "none" : params.size() + " cmds"));
        final List<String> captured = (params == null || params.isEmpty())
                ? null : params;
        mHandler.post(() -> enterStep1(gen, pkg, captured));
    }

    /** 步骤 1: 清场 + 回 ABYP */
    private void enterStep1(final int gen, final String pkg, final List<String> params) {
        if (gen != mGeneration) {
            return;
        }
        runAll(mConfig.getOffCommands());
        IrisCommandExecutor.exec(CMD_ABYP);
        mHandler.postDelayed(() -> enterStep2(gen, pkg, params), DELAY_ABYP_SETTLE_MS);
    }

    /** 步骤 2: 面板锁 120Hz (MEMC 投票) */
    private void enterStep2(final int gen, final String pkg, final List<String> params) {
        if (gen != mGeneration) {
            return;
        }
        mMainHandler.post(() -> {
            if (gen == mGeneration) {
                mCallback.lockPanelForMemc();
            }
        });
        mHandler.postDelayed(() -> enterStep3(gen, pkg, params), DELAY_PANEL_LOCK_MS);
    }

    /** 步骤 3: 切 PT 工作模式 */
    private void enterStep3(final int gen, final String pkg, final List<String> params) {
        if (gen != mGeneration) {
            return;
        }
        IrisCommandExecutor.exec(CMD_PT);
        mHandler.postDelayed(() -> enterStep4(gen, pkg, params), DELAY_PT_SETTLE_MS);
    }

    /** 步骤 4: 写应用 MEMC 参数 */
    private void enterStep4(final int gen, final String pkg, final List<String> params) {
        if (gen != mGeneration) {
            return;
        }
        if (params != null && runAll(params)) {
            mState = STATE_ACTIVE;
            Log.i(TAG, "MEMC active: " + pkg);
        } else {
            Log.e(TAG, "MEMC params failed/empty → safe exit");
            doExit(gen);
        }
    }

    // ------------------------------------------------------------------
    // 退出序列
    // ------------------------------------------------------------------

    private void requestExit() {
        final int gen = ++mGeneration;
        mState = STATE_EXITING;
        mHandler.post(() -> doExit(gen));
    }

    private void doExit(final int gen) {
        if (gen != mGeneration) {
            return;
        }
        mState = STATE_IDLE;
        String pkg = mActivePackage;
        mActivePackage = null;
        if (pkg != null) {
            Log.i(TAG, "exit MEMC: " + pkg);
        }
        // 1. 清功能
        runAll(mConfig.getOffCommands());
        // 2. 解锁刷新率 (主线程清投票重算)
        mMainHandler.post(() -> {
            if (gen == mGeneration) {
                mCallback.unlockPanel();
            }
        });
        // 3. 回 ABYP 安全态 (防冻结根基, 必须兜底)
        IrisCommandExecutor.exec(CMD_ABYP);
        Log.i(TAG, "back to ABYP");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private boolean runAll(List<String> cmds) {
        boolean ok = true;
        for (String c : cmds) {
            if (!IrisCommandExecutor.exec(c)) {
                ok = false;
            }
        }
        return ok;
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                    && mState != STATE_IDLE) {
                Log.i(TAG, "screen off → MEMC exit (safety)");
                requestExit();
            }
        }
    };

    /** 服务销毁: 同步兜底退出 (确保退出命令真正发出后再收工) */
    public void shutdown() {
        mEnabled = false;
        final int gen = ++mGeneration;  // 作废所有进行中的序列
        mState = STATE_IDLE;
        mActivePackage = null;
        if (mAvailable) {
            Thread exit = new Thread(() -> {
                runAll(mConfig.getOffCommands());
                IrisCommandExecutor.exec(CMD_ABYP);
            }, "ORR-IrisExit");
            exit.start();
            try {
                exit.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            mContext.unregisterReceiver(mScreenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        mHandler.getLooper().quitSafely();
        Log.i(TAG, "shutdown, back to ABYP");
    }
}
