# 蓝牙按键互联（BLE Button Link）

把蓝牙按键 / 旋钮变成**可编程的车控遥控器**：每个手势（单击 / 双击 / 长按 / 左旋 / 右旋…）都能绑定一组车控或系统动作。支持声明式「共创」配置扩展新设备，更内置**自学习**——现场对同一动作教几遍即可用，无需预置协议。

> 面向自己车机的适配研究项目。ecarx、小米 / 米家等商标归各自所有；如涉侵权，私信即下架。

---

## ⚠️ 重要：公签版车机专用

本应用使用 **Android 平台签名**（AOSP platform test key）并声明 `android:sharedUserId="android.uid.system"`，以 system uid 身份运行、直接调用车机 framework 车控 API。

因此：

- **只能装在持有相同平台签名的车机上**（即车机 ROM 用 AOSP 公开 testkey 签名，或你自己重签的系统）。
- **普通手机、其他签名的车机无法安装 / 无法运行**（签名与 sharedUserId 冲突会被系统拒绝）。
- 仓库内 `platform.keystore` 即 AOSP 公开测试密钥（密码 `android`），本就是公开物料，仅用于本类研究。

---

## 适用产品 / 设备

运行时按 **MAC 命中已接入设备**，只要 MAC + 产品 + 密钥正确即可生效。支持的设备类型：

| 类型 | 说明 | 举例 |
|---|---|---|
| **加密米家按键（MiBeacon / FE95）** | 需 BindKey（32 位十六进制）。App 原生 AES-CCM 解密广播，按 objId + value 识别手势。 | 米家蓝牙旋钮、蓝牙按键等 |
| **明文广播按键（advmatch）** | 无需密钥，按广播字节 offset/mask 匹配。 | 各类明文 BLE 按键 |
| **自学习设备** | 上述加密设备即使没有预置 Profile，也可现场教一遍自动生成规则。 | 任意被动广播型米家按键 |

**不支持**：需 GATT 连接交互才上报的设备；没有任何稳定判别字节的设备。

新设备可通过「共创」声明式 JSON Profile 扩展（`app/src/main/assets/profiles/*.json`），无需改代码。导出分享的 Profile **不含** BindKey，可安全公开。

### 附带能力：车外喊话（PA 实时喊话）

内置一路车外喊话：把车内麦克风的实时音频喂到车机「车外喇叭 / 行人提示喇叭」，一按开、再按关。可绑定到任意蓝牙手势（动作「车外喊话（切换）」），也能在「设置 → 车外喊话」手动开关。

- 路由走车机 ecarx `Policy` 的车外音频属性（`USAGE_OCC_MIC` / `CONTENT_TYPE_OCC`），`AudioRecord → AudioTrack` 直通。
- 需要 `RECORD_AUDIO` 权限，**仅用于车外喊话即时播放，录音不保存、不上传**。依赖车机具备车外喇叭 / PA 通道，无该通道的机型不生效。

---

## 如何使用

1. **装好并授予权限**：安装后授予蓝牙 / 定位权限（BLE 扫描需要）。App 常驻前台服务被动扫描，开机自启。
2. **添加设备**（首页「＋」）：三条路任选——
   - 从米家账号设备列表选（登录后自动带出 BindKey）；
   - 手动输入 MAC + BindKey；
   - 扫描附近广播后选择，再指定产品型号与密钥。
3. **取 BindKey**：加密米家设备需要。可登录米家账号自动获取，或手动填 32 位十六进制。凭据**仅存内存、用完即弃**，不落盘。
4. **绑定动作 / 自学习**（设备卡片进入「按键编排」）：
   - 已知产品：直接给单击 / 双击 / 长按等手势绑定车控 / 打开应用 / 网址导航 / 执行命令。
   - 未知或想更精细：点「自学习手势」→「学习新动作」，对同一动作**连续操作 3 次**，App 多帧求稳定字节自动生成签名（如区分左旋 / 右旋），命名后即可绑定。自学习动作与内置手势**并存**。
5. **测试**：每个手势旁有「测试」按钮，不用真按设备即可验证绑定。也可用「设置 → 抓包助手」查看解密出的 objId + value。
6. **保存**：保存后服务自动重载，实体按键即刻生效。

---

## 构建

需要两样仓库外物料：

### 1. 车机厂商专有 API（ecarx-adaptapi.jar）——自行提取

车控依赖 ecarx 车机 framework 的 `com.ecarx.xui.adaptapi.*`，该 jar 为厂商专有、**不随本仓库分发**，请从自己的车机提取：

```bash
# 从车机 framework 拉取（文件名以实际为准，常见 ecarx.xui.adaptapi*.jar / .apk）
adb shell ls /system/framework | grep -i adaptapi
adb pull /system/framework/ecarx.xui.adaptapi.jar

# 若拉到的是 .jar（含 classes.dex）需转成含 .class 的编译期 jar：
#   用 dex2jar 或 jadx 反编译取类，打成仅供 compileOnly 引用的 stub jar 即可（不打包进 APK）。
```

把结果放到 `app/libs/ecarx-adaptapi.jar`。它在 `build.gradle` 里是 `compileOnly`，**只编译期引用、不会打包进 APK**（运行时用车机自带的）。

### 2. 编译

平台签名密钥 `platform.keystore` 已含在仓库（AOSP 公开测试钥）。

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

预编译 APK 见 [Releases](../../releases)。

---

## 致谢

- 米家云端登录与 BindKey 拉取的算法（登录三步、`ssecurity` 派生、`rc4`/签名等）参考自开源项目 **[Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)**（作者 Piotr Machowski），本项目据其思路以 Java 重新实现。感谢原作者。

## 打赏支持

如果这个项目对你有帮助，欢迎请我喝杯咖啡 ☕

<img src="docs/donate.jpg" width="480" alt="微信 / 支付宝 打赏码" />

## 免责声明

- 本项目为**个人车机适配 / 学习研究**用途，按自己车机逆向适配，不保证适用于其他机型。
- ecarx、小米 / 米家等名称与商标归各自权利人所有，本项目与之无隶属关系。
- 平台签名密钥为 AOSP 公开测试密钥，公开可得。
- 如认为存在侵权，请私信作者，将第一时间下架相关内容。
