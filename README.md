# 课业日程

课业日程是一款 Android 学习任务管理应用，支持从学习通、中国大学 MOOC 和学校云 SPOC 同步课程任务。

## 平台状态

- **Android**（当前开发主线），支持 Android 8.0 及以上版本。
- **Windows**（开发中）。

## Android 版

- 在手机上查看作业、测验和考试的状态与截止时间。
- 支持手动刷新、后台扫描、每日待办汇总和截止前提醒。
- 使用 Android Keystore 加密保存账号密码，任务数据保存在手机上。

手机端源码位于 [android/](android/)。使用、构建和测试步骤见 [Android 版说明](android/README.md)。

## 本地数据与许可证

提交改动时请使用虚构的账号和课程数据。[.gitignore](.gitignore) 已配置为忽略本地账号配置、会话、日志和签名密钥。

项目代码采用 [MIT 许可证](LICENSE)。Gradle Wrapper 采用 [Apache License 2.0](android/gradle/wrapper/LICENSE)。
