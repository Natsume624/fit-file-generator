# .fit文件生成器

————Natsume

基于 [IAMAI-Dev/KeepTrack](https://github.com/IAMAI-Dev/KeepTrack) 的本地定制版本（2.2.0），保留 GPL-3.0 许可证。支持免 Key 地图校准、GPX 原路线导入、平均步频设置和批量导出。

## Android 原生版

仓库的 [`android`](android/) 目录包含面向 Android 8.0 及以上设备的原生应用。它支持免 Key 地图校准、GPX 分段导入与原路线生成，并通过系统文件选择器直接保存 FIT；不需要账号、服务器或存储权限。构建、安装范围与隐私说明见 [Android 说明](android/README.md)。

## 使用

双击桌面的 `.fit文件生成器.exe`。填写距离、时长和平均步频（双脚总步数/分钟）。设置开始时间与保存目录，可批量生成文件。

最方便的方式：点击“打开地图”，在浏览器中搜索学校/体育场，放大地图，点击跑道中央或拖动蓝色中心点。调整方向、直道长度和弯道半径后，点击“应用到 .fit 文件生成器”，位置与尺寸会自动回填到桌面程序。原来的手动经纬度输入仍然保留。

地图使用 Leaflet 1.9.4 和 OpenStreetMap 标准底图，地点搜索使用 Photon。不需要 API Key。地图不是卫星影像，部分地区的跑道细节可能不完整；不能据此保证测量级精度。底图加载和搜索需联网，服务可能因网络或服务端限制暂时不可用。Leaflet 随 EXE 打包，GPX 导入和 FIT 导出可离线运行。

手动输入时，坐标默认来自高德或腾讯（GCJ-02）；地图回填后自动设为 WGS84，不重复转换。纬度、经度分开填写，取跑道中心而非入口或跑道边缘。

## GPX 导入

点击“导入 GPX”，选择自己的历史轨迹文件，再确认文件的坐标来源。标准 GPX、GPS 设备导出及 running_page 已做转换的文件应选 WGS84；只有明确未经转换的 GCJ-02 坐标才选高德/腾讯。避免重复转换导致偏移。

导入后直接沿选中的原路线按距离插值，不必输入操场中心。距离按 GPX 经纬度计算并锁定，不拉伸路线、不自动闭合或重复绕圈。时长、开始时间、步频使用当前输入；原 GPX 的时间和生理数据不会被原样复制。多个 trkseg / route 分段分别列出，默认第一段，可在右侧下拉框切换，不会强行拼接断开的路线。地图中绿色是起点、橙色是终点。切回标准跑道后可以继续地图校准。

GPX 只在本机读取。浏览器查看时，路线点仅通过回环地址传给本机浏览器，不发送给地图/搜索服务；第三方底图服务仍会收到当前视野的瓦片请求，搜索服务会收到用户输入的地点名称。浏览器校准页依赖桌面程序保持运行，关闭程序后页面失效。

根据实际跑道设置单段直道长度、半圆弯道半径和方位角。方位角表示跑道长轴自正北顺时针旋转的角度，0° 为南北方向，90° 为东西方向。默认 84.39 米直道、36.8 米半径，对应约 400 米一圈；这只是几何默认值，并非所有操场的实际尺寸。中心坐标默认留空，以免错误套用旧操场位置。

预览是北向朝上的跑道示意图，不是卫星底图。轨迹按沿线距离等速采样，取消随机漂移，使用 WGS84 椭球测地线定位；GCJ-02 输入迭代转换后再写入 FIT。转换数值误差测试不能替代现场测量，实际贴合程度仍取决于输入坐标、跑道尺寸、方向和地图底图。

## 数据

平均步频同时写入逐点、lap 和 session。FIT 内部使用 strides/min，两步为一个周期：171 步/分钟写为 85 + 0.5，并写入对应 fractional cadence。保留距离、速度、步长、心率、功率、触地时间、海拔和热量字段；心率等辅助数据为模型估计，不是实测。均匀速度和步频保证汇总与逐点数据一致。

第三方运动平台对 FIT 字段的读取方式可能不同；本版本已验证文件解码和数值一致性，没有进行账号上传验证。

## 源码运行、测试与打包

```powershell
uv sync
uv run python -m unittest discover -p "test_*.py"
uv run main.py
uv run --with pyinstaller pyinstaller --noconfirm --onefile --windowed --name .fit文件生成器 --add-data "web;web" main.py
```

自动测试覆盖：坐标转换、跑道闭合和连续性、方向旋转、步频编码、FIT 编解码、GPX 分段与无效文件、路线端点、地图回填与来源校验。地图服务仅绑定 127.0.0.1，使用随机会话地址、同源校验和浏览器内容安全策略，不对局域网开放。GPX 读取、地图请求、远端搜索响应和批量记录数均设有上限，以避免异常输入耗尽本机资源。安全问题请按 [安全策略](SECURITY.md) 私下报告。

## 技术参考

- [高德坐标系统说明](https://lbs.amap.com/api/javascript-api-v2/guide/abc/basetype)
- [Garmin FIT 步频单位说明](https://forums.garmin.com/developer/fit-sdk/f/discussion/288454/fractional-cadence-values/1391870)
- 坐标转换依赖 coordtransform；测地线计算依赖 GeographicLib；FIT 编解码依赖 fit-tool。
- 地图交互参考 FIT-Tool 的功能思路，代码为本项目独立实现，未复制其源码。
- [Leaflet](https://leafletjs.com/)：BSD-2-Clause，许可证附于 web/vendor/LICENSE-Leaflet。
- [OpenStreetMap 瓦片使用政策](https://operations.osmfoundation.org/policies/tiles/)：显示署名、遵守浏览器缓存、仅加载当前视野，不提供批量预下载。
- [Photon](https://github.com/komoot/photon)：仅在点击搜索时查询，限频并缓存；公共服务不保证可用性。
