# 课业日程 · Android

手机端读取学习通、中国大学 MOOC 和学校云 SPOC 的课程任务。Android 8.0 或更新版本可以安装。任务和网页会话保存在手机上，无需运行 Windows 扫描器。

## 使用方法

1. 在「平台连接」点相应平台的「账号」，填写用户名和密码，再点「验证登录」。验证完成后点「保存」，应用会开始扫描。保存的账号密码使用 Android Keystore 在本机加密保存。
2. 点「立即扫描」可随时更新。每轮扫描都会用已保存的账号密码重新建立平台会话。需要验证码或其他验证时，应用会显示登录状态并保留本机数据。点任务卡可打开任务详情。平台数据无法确定任务直达链接时，打开相应的任务列表。截止时间取自任务列表，没有日期时显示为未知。
3. 在设置中启用「后台扫描」，将间隔设为 15–1440 分钟。小米和 Redmi 手机可从「自启动设置」允许「课业日程」在后台自启动。系统休眠、省电或网络受限时，Android 可能延后扫描。重新打开应用时会恢复周期调度，若已联网且有平台超过扫描间隔未成功更新，应用会安排一次补扫。系统设置中的「强行停止」会暂停后台任务。
4. 可设置每日待办汇总和截止前提醒。每日汇总按手机时区，平台截止日期按北京时间解析。设置页显示通知总开关、通知频道、精确提醒授权和当前系统报告的后台限制，可用「1 分钟后试提醒」检查系统投递。未授权精确提醒时，Android 可能延后通知。

学习通通过课程目录和学生学习记录的 JSON 接口，逐门分页读取学生课程的作业和考试，包括历史课程。首页显示成功读取的课程数。某个列表读取失败时保留该列表的上次任务，其他列表正常更新。MOOC 和 SPOC 通过平台接口读取课程目录、测验、作业和考试。点「立即扫描」后，首页会显示当前正在处理的平台。

每轮学习通扫描先用已保存的密码通过官网的登录接口建立新会话，再用课程目录接口确认登录。平台要求验证码、额外身份验证或更新密码时，应用会报告登录状态并保留已同步的任务。MOOC 和 SPOC 等待网易官网登录组件初始化成功后才提交密码，再通过 API 扫描。

后台扫描使用 Android JobScheduler 的持久化任务。扫描失败或会话过期时保留上次任务，部分成功时合并已读取的数据。平台接口无法完整返回数据时，应用会显示「部分更新」或「扫描失败」。

每日汇总在手机本地日期内最多发送一次，当天已安排的汇总可在原定时间后两小时内补发。截止提醒按平台、任务和截止时间去重。已安排的截止提醒可补发至任务截止后两小时。

## 构建和测试

在 Android Studio 打开本目录，使用内置 JDK，确认 SDK Manager 安装 Android 16（API 36）和 SDK Build-Tools 36。选择 API 36 手机模拟器或已开启 USB 调试的手机，运行 `app`。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
node --test tests/password-login.test.cjs
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。测试覆盖学习通历史课程、任务分页和列表失败后的缓存保留，MOOC / SPOC 的课程分页、任务读取和空考试列表，以及后台任务和通知。接口测试使用模拟课程数据。

在专用测试模拟器上，也可以直接用 ADB 安装并运行测试。`-g` 为测试应用授予通知权限。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.homework.mobile.test/android.test.InstrumentationTestRunner
```
