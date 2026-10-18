/*
 * Copyright (C) 2026 Oplus Project
 *
 * 帮助页 — WebView 加载本地 assets 帮助文档, 按系统语言自动选择:
 *   繁体中文 (zh-TW/zh-HK) → help_zh_tw.html
 *   简体中文 (zh-*)        → help_zh.html
 *   其他                   → help_en.html
 * 支持 intent extra "anchor" 直接跳到某节 (如 "t258")。
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.refreshrate.settings;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

import com.oplus.refreshrate.R;

import java.util.Locale;

public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.help_title);

        WebView web = new WebView(this);
        setContentView(web);

        String anchor = getIntent().getStringExtra("anchor");
        String url = "file:///android_asset/" + helpAssetForLocale();
        if (anchor != null && !anchor.isEmpty()) {
            url += "#" + anchor;
        }
        web.loadUrl(url);
    }

    private static String helpAssetForLocale() {
        Locale loc = Locale.getDefault();
        String lang = loc.getLanguage() == null ? "" : loc.getLanguage().toLowerCase(Locale.ROOT);
        if ("zh".equals(lang)) {
            String region = loc.getCountry() == null ? "" : loc.getCountry().toUpperCase(Locale.ROOT);
            return ("TW".equals(region) || "HK".equals(region) || "MO".equals(region))
                    ? "help_zh_tw.html" : "help_zh.html";
        }
        // 默认英文
        return "help_en.html";
    }
}
