# OplusRefreshRate — Vendor HAL 实施方案 (v3 最终)

> **选定方案 C**: 独立 vendor AIDL HAL, 系统级刷新率管理服务。
> 参考模板: `hardware/lineage/interfaces/livedisplay/aidl` (LineageOS LiveDisplay HAL)

## 一、架构总览

```
[客户端: 设置 App / SystemUI / 系统服务]
        │ AIDL binder
        ▼
[vendor.oplus.refreshrate HAL]  ← /vendor/bin/hw/vendor.oplus.refreshrate-service
        ├── RefreshRateCore (投票决策, 纯 C++)
        ├── SceneMonitor (场景感知: 前台应用/触摸/动画)
        ├── ConfigParser (原厂 refresh_rate_config.xml + oplus_vrr_config.json)
        └── Backends (多后端, 自动探测):
              ├── SF: SurfaceFlinger setDesiredActiveMode
              ├── SYSFS: /sys/kernel/oplus_display/dsi_cmd
              ├── DRM: /sys/class/drm/card0-DSI-1/modes
              └── IDisplayConfig: vendor AIDL (隐藏 secondary)
```

## 二、为什么是 vendor AIDL HAL (稳定不挂)

| 特性 | 独立 app (旧) | **vendor HAL (新)** |
|------|--------------|-------------------|
| 生命周期 | 可被杀 (START_STICKY 重启) | **init 管理, 崩溃自动拉起** |
| 权限 | system_app 域限制 | **vendor 域, 高权限** |
| 开机自启 | 需 BOOT_COMPLETED | **init late_start, 开机即起** |
| 保活 | 前台通知/后台限制 | **无限制 (native 服务)** |
| 资源 | Java app 进程 | **原生 C++, 低开销** |
| 稳定性 | 受 LMK/电池优化影响 | **不受影响** |

## 三、目录结构

```
vendor/oneplus/refreshrate/
├── Android.bp               # aidl_interface + cc_binary + soong_namespace
├── config.mk                # 设备树 inherit-product 入口
├── aidl/                    # AIDL 接口定义
│   └── vendor/oplus/refreshrate/
│       ├── IRefreshRate.aidl        # 主接口 (查询/设置刷新率)
│       ├── RefreshRateMode.aidl     # 模式枚举
│       └── IRefreshRateCallback.aidl # 回调 (可选)
├── default/
│   ├── Android.bp
│   ├── service.cpp          # main(): 检测环境 → 注册服务 → joinThreadPool
│   ├── RefreshRate.cpp      # AIDL 实现
│   ├── core/
│   │   ├── RefreshRateCore.h/.cpp   # 投票决策 (移植 Java 版)
│   │   ├── ConfigParser.h/.cpp      # XML/JSON 配置
│   │   └── SceneMonitor.h/.cpp      # 场景感知
│   └── backend/
│       ├── Backend.h/.cpp           # 后端接口 + 自动探测
│       ├── SfBackend.h/.cpp         # SurfaceFlinger
│       ├── SysfsBackend.h/.cpp      # oplus_display sysfs
│       └── DrmBackend.h/.cpp        # DRM modes
├── vendor.oplus.refreshrate-service.rc   # init 自启
├── sepolicy/
│   └── vendor/
│       ├── vendor_oplus_refreshrate.te   # HAL 域
│       └── file_contexts
└── config/
    ├── refresh_rate_config.xml     # 原厂配置
    └── oplus_vrr_config.json
```

## 四、AIDL 接口设计

```aidl
// vendor/oplus/refreshrate/IRefreshRate.aidl
package vendor.oplus.refreshrate;

@VintfStability
interface IRefreshRate {
    // 查询支持
    int[] getSupportedRates();
    // 查询当前刷新率
    int getCurrentRate();
    // 设置模式: 0=auto, 1=60, 2=90, 3=120 (锁定)
    void setMode(int mode);
    int getMode();
    // 后端查询/切换
    int[] getBackends();        // 返回可用后端
    int getActiveBackend();
    void setBackend(int backend);
    // 隐藏 secondary display (与 ColorOS 一致)
    void hideSecondaryDisplay();
    // 配置重载
    void reloadConfig();
    // 调试
    String dump();
}
```

## 五、后端设计 (全支持, 自动探测)

| 后端 | 优先级 | 探测条件 | 实现 |
|------|--------|---------|------|
| SF | 1 | SurfaceFlinger 服务存在 | setDesiredActiveMode(modeId) |
| SYSFS | 2 | /sys/kernel/oplus_display/ 存在 | write dsi_cmd / adfr_config |
| DRM | 3 | /sys/class/drm/card0-DSI-1/ 存在 | 读 modes, 写 (需 root/高权限) |
| IDisplayConfig | 附加 | vendor AIDL 服务存在 | setDisplayStatus (隐藏 secondary) |

**探测逻辑**: 启动时依次检查, 取第一个可用后端; 运行中后端失效自动降级。

## 六、环境检查 (不支持就退出)

service.cpp main():
```cpp
int main() {
    // 1. 检查支持性
    if (!hasOplusDisplay() && !hasDrmModes()) {
        LOG(ERROR) << "Device not supported, exiting";
        return 1;  // 退出, init 不会拉起 (oneshot)
    }
    // 2. 加载配置
    if (!loadConfig()) LOG(WARNING) << "No config, using defaults";
    // 3. 探测后端
    auto backend = probeBackend();
    if (!backend) {
        LOG(ERROR) << "No backend available, exiting";
        return 1;
    }
    // 4. 注册服务
    AServiceManager_addService(...);
    ABinderProcess_joinThreadPool();
}
```

## 七、rc 文件 (开机自启 + 稳定)

```rc
service vendor.oplus.refreshrate /vendor/bin/hw/vendor.oplus.refreshrate-service
    class late_start
    user root              # 高权限 (vendor HAL)
    group root
    oneshot               # 失败退出不自动重启? 否 — 用 restart 保证稳定
    # 注意: oneshot 与"不挂"冲突, 应让 init 管理重启
```

**稳定策略**:
- `class late_start` 开机自启
- **不加 oneshot** → 服务崩溃后 init 自动重启 (默认 4 次/秒内, 之后退避)
- `user root` → 高权限访问 sysfs (也可用 system, 视后端)

## 八、迁移步骤

1. 在 `vendor/oneplus/refreshrate/` 建仓库 (参考 vendor/oneplus/ir)
2. 写 aidl_interface + AIDL 文件
3. 写 C++ 实现 (core + backend)
4. 移植现有 Java 逻辑 (RefreshRateCore/ConfigParser/SceneMonitor)
5. 写 rc + vintf + sepolicy
6. config.mk (PRODUCT_PACKAGES + COPY_FILES)
7. 设备树: `$(call inherit-product, vendor/oneplus/refreshrate/config.mk)`
8. 移除设备树 parts/refreshrate (旧 Java app)
9. 编译验证

## 九、兼容性 (AOSP 13-16)

- 纯 AIDL + C++, 无 framework patch
- 后端缺失自动降级 (无 oplus_display → DRM/SF)
- 配置缺失 → 内置默认 (60/90/120)
- 不支持设备 → 退出 (init 不重启)

## 十、待办
- [ ] AIDL 接口文件
- [ ] C++ 核心 (移植 Java)
- [ ] 后端实现
- [ ] sepolicy + vintf
- [ ] 编译验证
