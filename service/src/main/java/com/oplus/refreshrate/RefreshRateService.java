/*
 * Copyright (C) 2026 Oplus Project
 *
 * Oplus RefreshRate Service — 刷新率调度服务
 *
 * 功能:
 *   1. 锁定模式: 用户选 60/90/120Hz, 系统固定该刷新率 (任务1)
 *   2. 智能模式: 场景感知投票调度 (任务2, OPlusRefreshRateSelector 移植)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.oplus.refreshrate.core.EnvironmentChecker;
import com.oplus.refreshrate.core.PerAppRuleConfig;
import com.oplus.refreshrate.core.RefreshRateApplier;
import com.oplus.refreshrate.core.RefreshRateConfigParser;
import com.oplus.refreshrate.core.RefreshRateCore;
import com.oplus.refreshrate.core.SceneMonitor;
import com.oplus.refreshrate.core.SecondaryDisplayHider;
import com.oplus.refreshrate.core.VrrConfigParser;
import com.oplus.refreshrate.iris.IrisConfigLoader;
import com.oplus.refreshrate.iris.IrisFeatureController;

import java.io.File;

/**
 * 刷新率调度服务 (前台服务, 保持运行)。
 */
public class RefreshRateService extends Service {
    private static final String TAG = "ORR/Service";

    // 动作 (由设置 app 或系统发送)
    public static final String ACTION_SET_MODE = "com.oplus.refreshrate.SET_MODE";
    public static final String EXTRA_MODE = "mode";  // 0=auto, 1=60, 2=90, 3=120
    public static final String ACTION_SET_BACKEND = "com.oplus.refreshrate.SET_BACKEND";
    public static final String EXTRA_BACKEND = "backend";
    public static final String ACTION_SET_IRIS = "com.oplus.refreshrate.SET_IRIS";
    public static final String EXTRA_ENABLED = "enabled";

    // 当前模式
    private int mMode = 0;  // 0=智能, 1=60Hz, 2=90Hz, 3=120Hz

    private RefreshRateCore mCore;
    private SceneMonitor mSceneMonitor;
    private RefreshRateConfigParser mRefreshRateConfig;
    private VrrConfigParser mVrrConfig;
    private IrisFeatureController mIrisController;
    private IrisConfigLoader mIrisConfig;
    private PerAppRuleConfig mPerAppConfig;

    /** 找第一个存在的配置文件 */
    private File findConfig(String... paths) {
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        mCore = new RefreshRateCore();

        // 静默后台服务: 提高线程优先级 (模拟原厂 OPlusVRRThread 优先级 -14)
        // 不显示通知, 不前台化, 保持后台运行 (debug 验证版)
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        // 默认后端: SF (无 root 也可用)
        RefreshRateApplier.init(this, RefreshRateApplier.BACKEND_SF);

        // 场景监听 (智能模式)
        mSceneMonitor = new SceneMonitor(this, mCore);

        // IRIS/MEMC 特性控制 (app 版, 开关默认关闭; 配置兼容 iris_helper JSON)
        mIrisConfig = new IrisConfigLoader();
        mIrisConfig.loadFirstAvailable();
        mIrisController = new IrisFeatureController(this, mIrisConfig,
                new IrisFeatureController.Callback() {
                    @Override
                    public void lockPanelForMemc() {
                        // 对齐 ColorOS: MEMC 投票优先级 70, 锁 120Hz (插帧输出帧率)
                        mCore.setVote(RefreshRateCore.Priority.MEMC,
                                new RefreshRateCore.Vote(120f, 120f, "memc"));
                        float rate = mCore.getPerfectRefreshRate(false);
                        if (rate > 0) {
                            RefreshRateApplier.apply(rate);
                        }
                    }

                    @Override
                    public void unlockPanel() {
                        // 对齐 ColorOS resetToAuto: 清 MEMC 投票, 回到常规调度
                        mCore.clearVote(RefreshRateCore.Priority.MEMC);
                        float rate = mCore.getPerfectRefreshRate(false);
                        if (rate > 0) {
                            RefreshRateApplier.apply(rate);
                        }
                    }
                });
        // 用户自定义 per-app 规则 (app_rules.json): 每个 app 的刷新率模式和行为
        mPerAppConfig = new PerAppRuleConfig();
        mPerAppConfig.loadFirstAvailable();

        // 前台调度统一入口: per-app 锁帧投票 + MEMC 决策 (规则可覆盖 iris_config)
        mSceneMonitor.addForegroundAppListener(pkg -> {
            mPerAppConfig.loadFirstAvailable();  // 热加载: 推完配置即生效
            PerAppRuleConfig.Rule rule = mPerAppConfig.getRule(pkg);

            // 1. 用户锁定刷新率 (USER_RULE=75, 高于 MEMC=70: 用户拍板优先)
            if (rule != null && rule.lockRate > 0) {
                mCore.setVote(RefreshRateCore.Priority.USER_RULE,
                        new RefreshRateCore.Vote(rule.lockRate, rule.lockRate,
                                "user:" + pkg));
            } else {
                mCore.clearVote(RefreshRateCore.Priority.USER_RULE);
            }

            // 2. MEMC 决策 (规则 memc/memc_params 可覆盖 iris_config)
            if (mIrisController != null) {
                mIrisController.onForegroundApp(pkg, rule);
            }

            // 3. 应用最终刷新率
            float rate = mCore.getPerfectRefreshRate(false);
            if (rate > 0) {
                RefreshRateApplier.apply(rate);
            }
        });
        Log.i(TAG, "per-app rules: " + mPerAppConfig.getLoadedPath());
        mIrisController.setEnabled(getSharedPreferences("rr", MODE_PRIVATE)
                .getBoolean("iris_enabled", false));
        Log.i(TAG, "iris: available=" + mIrisController.isAvailable()
                + " enabled=" + mIrisController.isEnabled()
                + " config=" + mIrisConfig.getLoadedPath());

        // 环境检查 (支持其他手机)
        EnvironmentChecker.Capabilities caps = EnvironmentChecker.check();
        if (!caps.supported) {
            Log.w(TAG, "environment not supported, service runs in safe mode");
            // 不支持时不隐藏 secondary, 仅提供锁定模式
        }

        // 加载原厂配置 (兼容 ColorOS 路径, 也支持自定义路径)
        mRefreshRateConfig = new RefreshRateConfigParser();
        mRefreshRateConfig.load(findConfig(
                "/my_product/etc/refresh_rate_config.xml",
                "/product/etc/refresh_rate_config.xml",
                "/data/system/refresh_rate_config.xml",
                "/system/etc/refresh_rate_config.xml"));
        mVrrConfig = new VrrConfigParser();
        mVrrConfig.load(findConfig(
                "/my_product/etc/oplus_vrr_config.json",
                "/product/etc/oplus_vrr_config.json",
                "/data/system/oplus_vrr_config.json",
                "/system/etc/oplus_vrr_config.json"));
        Log.i(TAG, "refresh configs: " + mRefreshRateConfig.size()
                + " apps, vrr v" + mVrrConfig.getVersion()
                + ", timeout=" + mVrrConfig.getTimeout());

        // 隐藏 secondary display (与 ColorOS 一致, 单 display 语义)
        // 仅在支持的环境中执行, 延迟等待 HWC/display 服务就绪
        if (caps.supported && caps.hasDisplayConfig) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(SecondaryDisplayHider::hideSecondary, 5000);
        }

        registerReceiver(mCommandReceiver, new IntentFilter(ACTION_SET_MODE),
                Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(mCommandReceiver, new IntentFilter(ACTION_SET_BACKEND),
                Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(mCommandReceiver, new IntentFilter(ACTION_SET_IRIS),
                Context.RECEIVER_NOT_EXPORTED);

        // 恢复上次模式
        mMode = getSharedPreferences("rr", MODE_PRIVATE).getInt("mode", 0);
        applyMode();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleAction(intent);
        }
        return START_STICKY;
    }

    private final BroadcastReceiver mCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleAction(intent);
        }
    };

    private void handleAction(Intent intent) {
        String action = intent.getAction();
        if (ACTION_SET_MODE.equals(action)) {
            int mode = intent.getIntExtra(EXTRA_MODE, 0);
            setMode(mode);
        } else if (ACTION_SET_BACKEND.equals(action)) {
            int backend = intent.getIntExtra(EXTRA_BACKEND, RefreshRateApplier.BACKEND_SF);
            RefreshRateApplier.init(this, backend);
            Log.i(TAG, "backend changed to " + backend);
        } else if (ACTION_SET_IRIS.equals(action)) {
            boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
            getSharedPreferences("rr", MODE_PRIVATE)
                    .edit().putBoolean("iris_enabled", enabled).apply();
            mIrisController.setEnabled(enabled);
        }
    }

    /** 设置模式: 0=智能, 1=60, 2=90, 3=120 */
    public void setMode(int mode) {
        mMode = mode;
        getSharedPreferences("rr", MODE_PRIVATE).edit().putInt("mode", mode).apply();
        Log.i(TAG, "mode set to " + mode);
        applyMode();
    }

    private void applyMode() {
        switch (mMode) {
            case 1:  // 锁定 60Hz
                mCore.clearAllVotes();
                mCore.setVote(RefreshRateCore.Priority.MAX,
                        new RefreshRateCore.Vote(60f, 60f, "lock60"));
                break;
            case 2:  // 锁定 90Hz
                mCore.clearAllVotes();
                mCore.setVote(RefreshRateCore.Priority.MAX,
                        new RefreshRateCore.Vote(90f, 90f, "lock90"));
                break;
            case 3:  // 锁定 120Hz
                mCore.clearAllVotes();
                mCore.setVote(RefreshRateCore.Priority.MAX,
                        new RefreshRateCore.Vote(120f, 120f, "lock120"));
                break;
            case 0:
            default:  // 智能
                mCore.clearAllVotes();
                // 恢复系统自动切换 (之前锁定设置了 NONE, 必须还原)
                RefreshRateApplier.resetToAuto();
                break;
        }
        // 立即应用
        float rate = mCore.getPerfectRefreshRate(false);
        if (rate > 0) {
            RefreshRateApplier.apply(rate);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mIrisController != null) {
            mIrisController.shutdown();  // 兜底回 ABYP, 防止带病退出
        }
        if (mSceneMonitor != null) {
            mSceneMonitor.shutdown();
        }
        unregisterReceiver(mCommandReceiver);
        super.onDestroy();
    }
}
