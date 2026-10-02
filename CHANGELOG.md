# 更新日志

## 2.3.0 · 2026-10-02

- Windows 与 Android 新增可选“步幅（米/步）”输入，留空保留自动计算。
- 跑道和 GPX 预览显示当前步幅及自动 / 自定义模式。
- 自定义步幅统一写入逐秒记录与 lap / session 汇总，增加范围校验与编解码测试。
- 修正 Android 编码器的 lap / session 平均步幅字段编号，确保可正常读取。
- 新增整个应用的完整操作说明，包含安装、地图、坐标来源、GPX、运动参数、批量保存和常见问题。
- Windows 和 Android 新增“操作说明”入口，内置 HTML 可离线阅读；说明同时随发布附件提供。
- Windows EXE、Android APK 和项目元数据统一升级至 2.3.0；Android 沿用原正式签名并递增版本代码。

## 2.2.0 · 2026-09-16

### Android 原生版

- 新增 Android 8.0 及以上设备可安装的原生 APK。
- 新增基于 Leaflet、OpenStreetMap 与 Photon 的免 Key 地图校准。
- 新增 GPX `trkseg` / `rte` 分段导入、分段选择、原路线预览与 FIT 生成。
- 支持 WGS84、GCJ-02 和 BD-09 三种 GPX 坐标来源。
- 使用 Android 系统文件选择器导入和保存，无需存储权限。
- FIT 在本机离线生成；GPX 不上传，地点关键词仅在用户主动搜索时发送给 Photon。

### 工程与验证

- 新增 Gradle Wrapper 和 GitHub Actions APK 构建工作流。
- 新增纯 Java FIT 编码与 GPX 解析冒烟测试。
- Android Lint、APK 签名校验及 Python `fit-tool` 反向解码验证通过。
