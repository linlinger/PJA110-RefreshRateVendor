/*
 * Copyright (C) 2026 Oplus Project
 *
 * 单应用规则编辑页 — 刷新率模式 + MEMC 行为 + 高级参数。
 * 保存即写 app_rules.json, 服务切前台热加载生效。
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.oplus.refreshrate.R;
import com.oplus.refreshrate.core.PerAppRuleConfig;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class PerAppEditActivity extends Activity {

    private static final String TAG = "ORR/PerAppEdit";

    private static final String SERVICE_RULE_PATH = "/data/system/orr/app_rules.json";

    private String mPkg;
    private String mLabel;

    private RadioGroup mModeGroup;
    private RadioGroup mMemcGroup;
    private EditText mParamsEdit;

    private PerAppRuleConfig mConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_per_app_edit);

        mPkg = getIntent().getStringExtra("pkg");
        mLabel = getIntent().getStringExtra("label");
        if (mPkg == null) {
            finish();
            return;
        }
        setTitle(mLabel != null ? mLabel : mPkg);
        ((TextView) findViewById(R.id.edit_pkg)).setText(mPkg);

        mModeGroup = findViewById(R.id.mode_group);
        mMemcGroup = findViewById(R.id.memc_group);
        mParamsEdit = findViewById(R.id.params_edit);

        mConfig = new PerAppRuleConfig();
        mConfig.loadFirstAvailable();
        prefill();

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_delete).setOnClickListener(v -> delete());
        findViewById(R.id.btn_help).setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, HelpActivity.class)
                    .putExtra("anchor", "cmd"));
        });
    }

    private void prefill() {
        PerAppRuleConfig.Rule r = mConfig.getRule(mPkg);
        if (r == null) {
            ((RadioButton) findViewById(R.id.mode_auto)).setChecked(true);
            ((RadioButton) findViewById(R.id.memc_default)).setChecked(true);
            return;
        }
        if (r.lockRate >= 119.5f) {
            ((RadioButton) findViewById(R.id.mode_120)).setChecked(true);
        } else if (r.lockRate >= 89.5f) {
            ((RadioButton) findViewById(R.id.mode_90)).setChecked(true);
        } else if (r.lockRate >= 59.5f) {
            ((RadioButton) findViewById(R.id.mode_60)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.mode_auto)).setChecked(true);
        }
        if (Boolean.TRUE.equals(r.memc)) {
            ((RadioButton) findViewById(R.id.memc_on)).setChecked(true);
        } else if (Boolean.FALSE.equals(r.memc)) {
            ((RadioButton) findViewById(R.id.memc_off)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.memc_default)).setChecked(true);
        }
        if (r.memcParams != null) {
            StringBuilder sb = new StringBuilder();
            for (String c : r.memcParams) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(c);
            }
            mParamsEdit.setText(sb);
        }
    }

    private void save() {
        float lockRate = -1f;
        int modeId = mModeGroup.getCheckedRadioButtonId();
        if (modeId == R.id.mode_60) {
            lockRate = 60f;
        } else if (modeId == R.id.mode_90) {
            lockRate = 90f;
        } else if (modeId == R.id.mode_120) {
            lockRate = 120f;
        }

        Boolean memc = null;
        int memcId = mMemcGroup.getCheckedRadioButtonId();
        if (memcId == R.id.memc_on) {
            memc = Boolean.TRUE;
        } else if (memcId == R.id.memc_off) {
            memc = Boolean.FALSE;
        }

        List<String> params = new ArrayList<>();
        String raw = mParamsEdit.getText().toString();
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                params.add(s);
            }
        }

        mConfig.setRule(new PerAppRuleConfig.Rule(mPkg, lockRate, memc,
                params.isEmpty() ? null : params));
        String json = mConfig.toJson();
        boolean ok = writeRules(json);
        Toast.makeText(this, ok ? R.string.per_app_saved : R.string.per_app_save_failed,
                Toast.LENGTH_SHORT).show();
        if (ok) {
            finish();
        }
    }

    private void delete() {
        mConfig.removeRule(mPkg);
        boolean ok = writeRules(mConfig.toJson());
        Toast.makeText(this, ok ? R.string.per_app_deleted : R.string.per_app_save_failed,
                Toast.LENGTH_SHORT).show();
        if (ok) {
            finish();
        }
    }

    /** 写规则文件: 正式路径 + 自身外部目录 (服务的 CONFIG_PATHS 都会尝试) */
    private boolean writeRules(String json) {
        boolean ok = writeFile(new File(SERVICE_RULE_PATH), json);
        try {
            File ext = new File(getExternalFilesDir(null), "app_rules.json");
            boolean ok2 = writeFile(ext, json);
            return ok || ok2;
        } catch (Exception e) {
            return ok;
        }
    }

    private boolean writeFile(File f, String content) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            try (FileWriter w = new FileWriter(f)) {
                w.write(content);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
