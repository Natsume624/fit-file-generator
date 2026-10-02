# FIT 文件生成器 · Android

这是面向 Android 8.0（API 26）及以上设备的原生版本。它不需要账号或服务器，FIT 文件在手机本地生成，并通过 Android 系统文件选择器保存到用户选择的位置。

安装和完整操作流程见 [完整操作说明](../USER_GUIDE.md)。主界面标题区的“操作说明”按钮可离线查看同一份说明；官方安装包与校验文件见 [v2.3.0 发布页](https://github.com/Natsume624/fit-file-generator/releases/tag/v2.3.0)。正式 APK 沿用原签名，版本代码更新为 230。

## 已实现

- 距离、时长、平均步频、自定义步幅和开始时间设置
- WGS84 跑道中心、方向、直道长度与弯道半径设置
- 原生跑道预览、配速/周长/圈数即时计算
- 使用设备最近位置快速填入坐标
- 内置 Leaflet + OpenStreetMap 免 Key 地图校准
- Photon 地点搜索，点击或拖动跑道中心并回填方向与尺寸
- GPX `trkseg` / `rte` 分段导入、分段切换和原路线地图查看
- WGS84、GCJ-02、BD-09 三种 GPX 坐标来源转换
- FIT activity、record、lap、session 与逐秒轨迹写入
- 奇数步频的 fractional cadence 写入
- 无存储权限保存：使用 Android Storage Access Framework
- 完全离线生成，不上传运动数据
- 内置离线完整操作说明和返回应用按钮

GPX 在本机读取，不上传轨迹。地图页面只向 OpenStreetMap 请求当前视野的瓦片；只有用户主动搜索时，地点关键词才会发送给 Photon。地图和搜索需要联网，FIT 生成与 GPX 解析可离线使用。

“步幅（留空自动）”的单位是米/步，例如 `0.80` 表示 80 厘米/步，允许范围为 0.01～6.5。留空时根据距离、时长与步频计算；填写时按自定义值写入逐秒记录与 lap / session 平均步幅。该设置同时适用于标准跑道和 GPX 原路线，预览会显示当前数值和模式。自定义步幅不会自动调整距离、时长或步频。

## 构建 APK

在 `android` 目录运行：

```bash
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。仓库的 **Android APK** GitHub Actions 工作流也会生成同名可安装构建产物。

正式发布使用 `android/.signing/keystore.properties` 指向本机 release signing keystore，然后执行 `assembleRelease`。`.signing` 已被 Git 忽略；不要把 keystore 或密码提交到仓库，并务必离线备份。后续 APK 更新必须使用同一签名密钥。

## 编码器冒烟测试

`FitEncoder` 不依赖 Android API，可用 JDK 17 单独编译，并将生成结果交给根目录 Python 环境中的 `fit-tool` 解码验证：

```powershell
javac --release 17 -encoding UTF-8 -d android/smoke-classes android/app/src/main/java/com/natsume/fitgenerator/FitEncoder.java android/app/src/main/java/com/natsume/fitgenerator/Route.java android/app/src/main/java/com/natsume/fitgenerator/GpxParser.java android/tools/EncoderSmoke.java
java -cp android/smoke-classes com.natsume.fitgenerator.EncoderSmoke android-smoke.fit android-route.fit android-custom.fit android-route-custom.fit
.\.venv\Scripts\python.exe android/tools/verify_encoder.py android-smoke.fit android-route.fit android-custom.fit android-route-custom.fit
```
