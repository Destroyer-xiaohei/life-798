# WaterWidget

WaterWidget 是一个面向慧生活798用户的第三方 Android 设备控制客户端，提供多账户管理、饮水设备启动与停止、钱包充值、用水消费统计、桌面小部件、快捷设置磁贴与设备二维码添加功能。(原项目地址[https://github.com/Destroyer-xiaohei/life-798.git](https://github.com/Destroyer-xiaohei/life-798))

> 非官方项目，与慧生活798服务提供方无关。请仅使用你本人有权访问的账户和设备，并遵守相关服务规则。

## 功能

- 饮水设备一键启动与停止：设备卡片按实时状态自动在「启动 / 停止」之间切换，停止需二次确认；主动停止会立即结束接水监测并撤掉进行中的通知
- 自动同步设备名称，支持设备别名、快捷移除和控制中心默认设备切换
- 短信登录与账户管理：设备登录用于同步和启动设备、钱包充值及 App 端任务；补充积分登录可完成支付宝端任务并获得更多积分
- 查看校园钱包余额，并通过支付宝完成充值
- 扫描二维码或手动添加设备编号
- 每日签到、积分任务与任务执行记录，通知栏实时显示任务进度与累计积分
- 本地优先的今日 / 本月 / 本年消费与预计饮水量统计
- 桌面小部件与快捷设置磁贴：都先检测设备真实出水状态，再决定显示并执行「启动」还是「停止」
- 浅色、深色和跟随系统的显示模式
- 积分托管：账号同步到自建服务端，服务端每天定时领取全部积分，App 内查看托管账号与领取日志

## 积分托管服务端

`server/` 目录是一个零第三方依赖的 Python 服务端（标准库即可运行）：

```bat
cd server
python app.py
```

启动后会打印监听地址与 ApiKey，把它们填进 App 的 **我的 → 自动领取**：

| 能力 | 说明 |
| --- | --- |
| 账号同步 | 把本机所有积分登录态的账号（token / uid / eid / 手机号）推给服务端；App 每次回到前台会静默重同步 |
| 每日定时 | 服务端内置调度器，默认每天 00:05（北京时间）领完全部账号；也可用 `--once` 挂系统计划任务 |
| 实时日志 | App 内「日志」页签按账号 / 级别查看每一步领取记录，支持自动刷新 |
| 手动触发 | 支持「立即领取全部」或对单个托管账号单独执行 |

接口契约、环境变量与领取逻辑见 `server/README.md`。

## 运行环境

- Android 13（API 33）及以上
- 仅提供 `arm64-v8a` 安装包，适用于现代 64 位 Android 设备

## 构建

项目采用 Gradle Kotlin DSL、Gradle 9.5、JDK 21、Kotlin 2.4 和 Jetpack Compose。源码通过 Gradle `sourceSets` 直接使用仓库根目录的 `src/main` 和 `src/test`。

### 配置参数

项目中的 API 地址、签名盐值等信息通过 `secrets.properties` 注入，**不会提交到版本控制**。构建前需手动创建：

1. 复制项目根目录的 `secrets.properties.example` 为 `secrets.properties`
2. 将其中的占位值替换为实际值

```bash
cp secrets.properties.example secrets.properties
# 然后编辑 secrets.properties 填入实际的 API_GATEWAY / SIGN_SALT / API_CID
```

> **说明**：未配置 `secrets.properties` 时项目仍可正常编译，但构建出的 APK 因缺少必要参数无法连接服务端。

### 构建命令

```bash
# JVM 单元测试
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug

# 正式 Release（需设置签名环境变量）
./gradlew assembleRelease
```

APK 默认输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 隐私与数据

应用的账户和设备配置保存在本机。请不要将设备控制登录信息、账户数据或二维码内容分享给他人。

## 致谢与第三方项目

- [miuix](https://github.com/compose-miuix-ui/miuix)：使用 `miuix-ui` 的下拉刷新组件和 `miuix-blur` 的模糊能力，并在应用内适配刷新布局与状态衔接；Apache-2.0 License。
- [QuickieExtended](https://github.com/T8RIN/QuickieExtended)：提供基于 CameraX 与 ML Kit 的二维码扫描能力；MIT License。

服务端为自建组件，随本仓库一起分发；账号凭据只保存在你自己的服务端与本机。

上述项目及其代码继续遵循各自的许可证，本项目的 MIT License 不替代其许可证。

## 免责声明

本项目按现状提供，不对可用性、准确性或使用结果作任何保证，使用风险由使用者自行承担。

## 许可证

本项目采用 [MIT License](LICENSE)。
