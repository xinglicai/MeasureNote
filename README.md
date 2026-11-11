# 诺瓦标记 MeasureNote

给照片加尺寸标注、做测量的安卓 App：拍一张照片，在上面画线、标尺寸、量角度，配上真实数值，最后导出成一份 A4 PDF 报告。

**零第三方依赖** —— 不用 androidx，不用 Gradle，纯 Java + Android Framework API，靠 `aapt2 + javac + d8` 手工构建。

还可以**直连博世激光测距仪**：在照片上画一条线，点「测距」，真机测出的距离自动填进标注。

---

## 功能

### 照片测量标注

| 类型 | 说明 |
|---|---|
| **线段** | 两点距离，可设真实长度 |
| **矩形** | 宽 × 高，自动算面积 |
| **圆** | 直径 / 半径，自动算面积 |
| **角度** | 三点定角，支持手动覆盖实测值 |
| **文字** | 自由注释 |

- 所有图形都**可拖动控制点改形状**，改完实时重算数值
- 12 色 + 4 档线宽 + 实线/虚线
- 选中标注后底部出现操作条，可改数值、删样式
- 撤销 / 重做（快照栈 60 步）

### 比例尺自动换算

在图上标一条**已知实际长度**的基准线（比如量一段已知 2 米的墙），之后画的每条线都自动换算成真实长度。

比例尺是**每张照片独立**的 —— 不同照片拍摄距离不同，本来就该分开。

> 这是从「贴标签」变成「真测量」的关键一步。

### 蓝牙激光测距仪

支持博世 GLM 系列（实测 GLM 50-27 CG），把真机测距结果直接填进标注。

- **自动扫描并识别**：扫描后按信号强度逐个连接验证服务 UUID，找到就自动连上
- **记住设备**：进编辑页自动重连上次用的测距仪
- **输入框内联测距**：画线后弹出的尺寸框里有「蓝牙测距」按钮，点一下测距仪响一声，数值直接填进输入框，对话框不关

### 项目管理

- 一个项目 = 一组照片 + 每张照片上的标注
- 单张或整个项目导出 PDF
- 导出到公共 `Download/MeasureNote/`

### PDF 报告

A4 每页一张图 + 标注清单，样式与屏幕所见一致。

---

## 构建

### 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | |
| Android SDK Build-Tools | **35.0.0** | 34 的 d8 处理匿名内部类会崩 |
| Android Platform | android-34 | 只用其 android.jar |
| keystore | 任意 debug keystore | |

### 一键构建

```powershell
# 改 build.ps1 顶部路径
$SDK       = "C:\path\to\android-sdk"
$JAVA_HOME = "C:\path\to\jdk-21"
$KEYSTORE  = "C:\path\to\debug.keystore"

powershell -File .\build.ps1
```

产物：`MeasureNote.apk`

---

## 安装

APK 在 [release/](release/) 目录：

```
release/诺瓦标记_v1.9.apk
```

传到手机安装即可。

---

## 代码结构

```
src/com/meownote/measure/
├── MainActivity.java       项目列表
├── ProjectActivity.java    单个项目内的照片网格
├── EditActivity.java       编辑页：工具栏、尺寸输入、样式面板、测距入口
├── AnnotationView.java     自绘画布：缩放平移、绘制、控制点拖动、气泡渲染
├── Project.java            数据模型（Project / Item / Ann 三层）+ JSON 序列化
├── Store.java              文件层
├── PdfExporter.java        PDF 导出
├── UnitUtil.java           单位换算（mm/cm/m/in/ft/px）
├── FileProvider2.java      极简文件共享 Provider
├── BleMeter.java           博世测距仪蓝牙驱动（XTP 协议）
└── BleMeterDialog.java     设备扫描 / 连接 / 测距界面
```

约 5600 行 Java。

### 几个实现要点

**坐标一律存图片原始像素**，显示和导出时经 Matrix 映射。这样缩放、旋转、导出 PDF 都不会错位。

**`manual` 标记**：照片测量一定有透视误差，所以允许用户手动覆盖数值。填过之后 `manual = true`，图形变化触发的自动重算一律跳过；再配一个「按图形重算」按钮解除锁定。既尊重实测，又保留自动推导。

**控制点拖动**：每种图元定义自己的控制点集合（线段两端、矩形对角、圆是圆心+圆周一点、角度三点），`ACTION_DOWN` 里按类型做距离命中测试返回 handle 索引。拖圆心时同步平移半径点以保持半径。

**列表项 margin 无效**：`ListView` 的 item 用 `AbsListView.LayoutParams`，`layout_marginBottom` 被忽略，要让卡片有间距得在外层套透明容器加 `paddingBottom`。

---

## 博世测距仪协议（XTP）

逆向自 MeasureOn v3.0.0 + GLM 50-27 CG 实测。协议实现在 `com.bosch.mtprotocol` 包里。

### 连接参数

| 项 | 值 |
|---|---|
| 服务 UUID | `02A6C0D0-0451-4000-B000-FB3210111989` |
| 特征 UUID | `02A6C0D1-0451-4000-B000-FB3210111989` |
| 模块 | Miraculix 1 |

### 帧格式

```
[MODE][CMD][LEN][DATA(LEN 字节)][CRC8]
```

- `MODE` = `0xC0`（LONG_REQ_LONG_RESP）
- `CRC8`：init `0xAA`、poly `0xA6`，覆盖 `MODE..DATA`

### 命令

| 方向 | 帧 | 含义 |
|---|---|---|
| TX | `c0 55 02 01 00 1a` | 握手 |
| TX | `c0 56 01 00 1e` | 触发测量 |
| RX | `c0 11 00 3a` | 心跳（每 1.5 秒） |
| RX | `c0 55 10 06 00 …` | 测量结果 |

测量结果 payload（16 字节）：

| 偏移 | 长度 | 含义 |
|---|---|---|
| 0 | 1 | 类型（`0x02` 开始确认 / `0x06` 结果） |
| 2–3 | 2 | 消息序号，16 位小端，每帧 +1 |
| 4–7 | 4 | **测量值 float32 小端（米）** |
| 8–15 | 8 | 保留 |

### 两个坑

**CCCD 必须用 INDICATION 不是 NOTIFICATION** —— 写 `ENABLE_NOTIFICATION_VALUE` 返回 `status=128`（GATT_FAILURE），收不到任何数据；换成 `ENABLE_INDICATION_VALUE` 才 `status=0`。判断通道是否真通：看 logcat 有没有 `c0 11 00 3a` 心跳。

**扫描不能加 Service UUID 过滤** —— 博世广播包里不带那个完整 UUID，加了过滤结果恒为 0。只能扫全部设备，连接后再靠服务发现验证。

---

## License

MIT
