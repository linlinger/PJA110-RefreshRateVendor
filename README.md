# OplusRefreshRate Vendor — PJA110 刷新率调度 (vendor 化)

一加 Ace 2 Pro (PJA110 / xigua) AviumUI 刷新率调度 + IRIS 独显管理的 **vendor 分区版**。
App 版 (`PJA110-RefreshRateService`, system_ext) 已封版, 后续功能全部在本仓库演进。

> 与 App 版**二选一**, 不要同时装入 ROM (同包名 `com.oplus.refreshrate`)。

## 当前状态 (P0 — vendor 分区应用)

- 代码自 App 版原样移植: 智能调度 (投票决策) / 全局锁定 60-90-120 / per-app 规则 GUI /
  MEMC 插帧 (ABYP↔PT 状态机, 时序对齐 ColorOS) / 帮助页 (iris 命令参数详解)
- **vendor 化差异**: `Android.bp` 改 `vendor: true, privileged: true` (装到
  `/vendor/priv-app/OplusRefreshRateVendor`), 增加 privapp 签名权限白名单
  (`sepolicy/vendor/privapp-permissions-com.oplus.refreshrate.xml`)
- 修复 App 版遗留问题: `Android.bp` 缺 `asset_dirs` 导致帮助页 HTML 打包不进 APK
- 多语言: 英文 (默认) / 简体中文 (values-zh-rCN) / 繁体中文 (values-zh-rTW), 帮助页同套三语

## ROM 集成

```mk
# device/oneplus/xigua 的 xxx.mk:
$(call inherit-product-if-exists, <本仓库路径>/config.mk)   # 待补 (P1)
```

手工步骤:
1. Android.bp 模块 `OplusRefreshRateVendor` 会被 `PRODUCT_PACKAGES` 引入
2. privapp 白名单复制到 `/vendor/etc/permissions/`
3. 从设备树移除旧 App 版模块与 `parts/refreshrate`

## 多语言

| 语言 | 资源 | 帮助页 |
|------|------|--------|
| English (default) | `values/` | `assets/help_en.html` |
| 简体中文 | `values-zh-rCN/` | `assets/help_zh.html` |
| 繁體中文 | `values-zh-rTW/` | `assets/help_zh_tw.html` |

HelpActivity 按系统语言自动选择; 新增字符串时三份 + 三个 HTML 同步加。

## 路线图

| 阶段 | 内容 | 状态 |
|------|------|------|
| P0 | vendor 分区应用 + 三语 + 帮助页 | ✅ 本提交 |
| P1 | 原彩显示 (Natural Tone): ColorOS 逆向 → per-app/场景色温调节 | 待做 (知识库: 逆向文档) |
| P2 | 视频长续航模式: 视频场景降分辨率/降刷/芯片省电档联动 | 待做 |
| P3 | 户外工作模式: 高亮度 + 高刷 + 触控采样联动 (逆向见知识库) | 待做 (知识库: 逆向文档) |
| P4 | C++ vendor AIDL HAL (docs/VENDOR_PLAN.md 方案 C): init 托管原生服务 | 待做 |
| P5 | sepolicy 完整化 + vintf + config.mk | 随 P4 |

配置文件 / iris 命令参考 / FAQ 见应用内「帮助与命令参考」(三语)。

## 相关仓库

- `PJA110-RefreshRateService` — App 版 (已封版, 源头)
- `android_device_oneplus_xigua` — 设备树
- 知识库 `~/Documents/AviumUI-Dev-Vault/Devices/PJA110/` — 逆向与排障文档
