# 更新日志

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
