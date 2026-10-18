/*
 * Copyright (C) 2026 Oplus Project
 *
 * Oplus RefreshRate Service — 净室实现
 *
 * 调度核心: 基于"投票 (Vote)"的刷新率决策。
 * 架构参考 ColorOS OPlusRefreshRateCore 的行为 (净室重写, 非 OPPO 代码):
 *   - 多个场景/组件各自"投票"请求一个刷新率
 *   - 每个投票有优先级 (0-150, 越高越优先)
 *   - 从最高优先级向下取, 高优先级 vote 可覆盖低优先级的 min/max 约束
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 刷新率投票核心 — 决定最终刷新率。
 *
 * 纯算法, 无 Android 依赖, 可独立测试。
 */
public final class RefreshRateCore {

    /**
     * 优先级常量 (对齐 ColorOS 的行为语义)。
     * 数值越大越优先。0-150 区间。
     */
    public static final class Priority {
        public static final int MIN = 0;
        public static final int APP_REQUEST = 10;        // 应用请求
        public static final int FLEXIBLE_WINDOW = 20;    // 自由窗口
        public static final int GAME_REQUEST = 25;       // 游戏请求
        public static final int ANIMATION_BOOST = 40;    // 动画加速
        public static final int HIGH_BRIGHTNESS = 50;    // 高亮度
        public static final int DC_MODE = 60;            // DC 调光
        public static final int MEMC = 70;               // 补帧
        public static final int USER_RULE = 75;          // 用户 per-app 规则 (用户拍板, 压过 MEMC)
        public static final int HIGH_TEMPERATURE = 80;   // 高温
        public static final int LAUNCHER_REQUEST = 85;   // 桌面请求
        public static final int OSYNC = 100;             // 系统同步
        public static final int LOWRATE_DISPLAY = 110;   // 低刷新率显示
        public static final int PAD_MIRAGE = 120;        // 平板投屏
        public static final int AOD = 121;               // 息屏显示
        public static final int FOD_SHOW = 130;          // 屏下指纹
        public static final int RATING_MODE = 140;       // 评分模式
        public static final int NEAR_FLASH = 150;        // 闪光灯 (最高)
        public static final int MAX = 150;
    }

    /** 优先级从高到低的遍历顺序 */
    private static final List<Integer> PRIORITIES = Arrays.asList(
            Priority.NEAR_FLASH, Priority.RATING_MODE, Priority.FOD_SHOW,
            Priority.AOD, Priority.PAD_MIRAGE, Priority.LOWRATE_DISPLAY,
            Priority.OSYNC, Priority.LAUNCHER_REQUEST, Priority.HIGH_TEMPERATURE,
            Priority.USER_RULE, Priority.MEMC, Priority.DC_MODE, Priority.HIGH_BRIGHTNESS,
            Priority.ANIMATION_BOOST, Priority.GAME_REQUEST,
            Priority.FLEXIBLE_WINDOW, Priority.APP_REQUEST, Priority.MIN);

    /** 单个投票: 一个场景/组件对刷新率的请求 */
    public static final class Vote {
        public final float refreshRate;      // 请求的刷新率 (Hz)
        public final float minRefreshRate;   // 最低可接受刷新率
        public final String requestSource;   // 请求来源 (调试用)

        public Vote(float refreshRate, float minRefreshRate, String requestSource) {
            this.refreshRate = refreshRate;
            this.minRefreshRate = minRefreshRate;
            this.requestSource = requestSource;
        }

        public Vote(float refreshRate, String requestSource) {
            this(refreshRate, refreshRate, requestSource);
        }

        @Override
        public String toString() {
            return "Vote{rate=" + refreshRate + ", min=" + minRefreshRate
                    + ", src='" + requestSource + "'}";
        }
    }

    /** 所有活跃投票, key = 优先级 */
    private final Map<Integer, Vote> mVotes = new HashMap<>();
    /** 是否冻结 (冻结时不允许刷新率变化) */
    private boolean mFreezing;

    /**
     * 决策: 从所有投票中选出最终刷新率。
     * 算法 (修复版, 语义与 ColorOS 一致):
     * 1. 从最高优先级向下遍历, 第一命中的 vote 即获胜候选
     * 2. 其余更低优先级投票只能提供 min 下限约束:
     *    若某投票的 minRefreshRate 高于候选值, 候选被抬升到该下限
     *    (低优先级不能把高优先级的结果拉低 — 修复此前"遍历方向反了,
     *    导致触摸 120Hz 票被 idle 60Hz 票短路"的 bug)
     */
    public float getPerfectRefreshRate(boolean topOnly) {
        Vote top = null;
        for (int i = 0; i < PRIORITIES.size(); i++) {
            top = mVotes.get(PRIORITIES.get(i));
            if (top != null) {
                break;
            }
        }
        if (top == null) {
            return -1.0f;
        }
        if (topOnly) {
            return top.refreshRate;
        }
        float rate = top.refreshRate;
        float minRate = top.minRefreshRate;
        for (Vote v : mVotes.values()) {
            if (v.minRefreshRate > minRate) {
                minRate = v.minRefreshRate;
            }
        }
        // 满足所有"最低可接受"约束: 不允许最终结果低于任何投票的 min
        return Math.max(rate, minRate);
    }

    /** 投票 (设置/更新一个优先级的投票) */
    public void setVote(int priority, Vote vote) {
        if (vote == null) {
            mVotes.remove(priority);
        } else {
            mVotes.put(priority, vote);
        }
    }

    /** 清除一个优先级的投票 */
    public void clearVote(int priority) {
        mVotes.remove(priority);
    }

    /** 清除所有投票 */
    public void clearAllVotes() {
        mVotes.clear();
    }

    /** 冻结/解冻 (冻结时刷新率锁定) */
    public void setFreezing(boolean freezing) {
        mFreezing = freezing;
    }

    public boolean isFreezing() {
        return mFreezing;
    }

    /** 当前最高优先级的投票优先级 */
    public int getCurMaxVotePriority() {
        for (int i = PRIORITIES.size() - 1; i >= 0; i--) {
            if (mVotes.get(PRIORITIES.get(i)) != null) {
                return PRIORITIES.get(i);
            }
        }
        return Priority.MIN;
    }

    /** 调试 dump */
    public String dumpVotes() {
        StringBuilder sb = new StringBuilder();
        for (int i = PRIORITIES.size() - 1; i >= 0; i--) {
            Vote vote = mVotes.get(PRIORITIES.get(i));
            if (vote != null) {
                sb.append("  [").append(PRIORITIES.get(i)).append("] ")
                        .append(vote).append('\n');
            }
        }
        return sb.toString();
    }
}
