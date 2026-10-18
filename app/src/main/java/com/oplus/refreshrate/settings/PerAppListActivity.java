/*
 * Copyright (C) 2026 Oplus Project
 *
 * per-app 规则应用列表 — 展示全部可启动应用, 点击进入单应用配置。
 * 配置写入 app_rules.json (服务切前台热加载):
 *   1. /data/system/orr/app_rules.json (privileged 签名可写)
 *   2. /sdcard/Android/data/com.oplus.refreshrate/files/ (fallback,
 *      服务的 CONFIG_PATHS 已包含, 无 root 环境也可用)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.oplus.refreshrate.R;
import com.oplus.refreshrate.core.PerAppRuleConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PerAppListActivity extends Activity {

    private static final String TAG = "ORR/PerAppList";

    private static final String SERVICE_RULE_PATH = "/data/system/orr/app_rules.json";

    private static final class AppEntry {
        String label;
        String pkg;
        Drawable icon;
        String ruleSummary;
    }

    private final List<AppEntry> mApps = new ArrayList<>();
    private AppAdapter mAdapter;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_per_app_list);
        setTitle(R.string.per_app_title);

        mAdapter = new AppAdapter();
        ListView lv = findViewById(R.id.per_app_list);
        lv.setAdapter(mAdapter);
        lv.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e;
            synchronized (mApps) {
                if (position >= mApps.size()) {
                    return;
                }
                e = mApps.get(position);
            }
            onAppClicked(e);
        });
        loadAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAppsAsync();  // 回来时刷新规则摘要 (编辑后生效)
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            List<AppEntry> apps = collectApps();
            synchronized (mApps) {
                mApps.clear();
                mApps.addAll(apps);
            }
            mMain.post(mAdapter::notifyDataSetChanged);
        }, "ORR-AppList").start();
    }

    private List<AppEntry> collectApps() {
        PerAppRuleConfig cfg = new PerAppRuleConfig();
        cfg.loadFirstAvailable();
        Map<String, PerAppRuleConfig.Rule> rules = cfg.getAllRules();
        Map<String, AppEntry> dedup = new HashMap<>();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo != null ? ri.activityInfo.packageName : null;
            if (pkg == null || dedup.containsKey(pkg)) {
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = String.valueOf(ri.loadLabel(pm));
            e.icon = ri.loadIcon(pm);
            e.ruleSummary = describeRule(rules.get(pkg));
            dedup.put(pkg, e);
        }
        List<AppEntry> list = new ArrayList<>(dedup.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private String describeRule(PerAppRuleConfig.Rule r) {
        if (r == null) {
            return getString(R.string.per_app_not_configured);
        }
        StringBuilder sb = new StringBuilder();
        if (r.lockRate > 0) {
            sb.append(getString(R.string.per_app_mode_prefix)).append(" ")
              .append((int) r.lockRate).append("Hz");
        } else {
            sb.append(getString(R.string.per_app_mode_auto));
        }
        if (Boolean.TRUE.equals(r.memc)) {
            sb.append(" · MEMC ").append(getString(R.string.per_app_memc_on));
        } else if (Boolean.FALSE.equals(r.memc)) {
            sb.append(" · MEMC ").append(getString(R.string.per_app_memc_off));
        }
        return sb.toString();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            synchronized (mApps) {
                return mApps.size();
            }
        }

        @Override
        public Object getItem(int position) {
            synchronized (mApps) {
                return mApps.get(position);
            }
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(PerAppListActivity.this)
                        .inflate(R.layout.item_per_app, parent, false);
            }
            AppEntry e;
            synchronized (mApps) {
                if (position >= mApps.size()) {
                    return v;
                }
                e = mApps.get(position);
            }
            ImageView icon = v.findViewById(R.id.item_icon);
            TextView name = v.findViewById(R.id.item_name);
            TextView summary = v.findViewById(R.id.item_summary);
            icon.setImageDrawable(e.icon);
            name.setText(e.label);
            summary.setText(e.ruleSummary);
            return v;
        }
    }

    void onAppClicked(AppEntry e) {
        Intent it = new Intent(this, PerAppEditActivity.class);
        it.putExtra("pkg", e.pkg);
        it.putExtra("label", e.label);
        startActivity(it);
    }
}
