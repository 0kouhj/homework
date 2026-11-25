# 学习任务扫描器

在 Windows 上定时读取学习通、中国大学 MOOC 和学校云 SPOC 的网页课程列表。扫描结果按课程分组，课程内按截止时间排序。

## Android 手机版

在 Android Studio 打开 [android](android/) 工程。手机可独立登录并扫描学习通、MOOC 和 SPOC，支持后台扫描间隔、每日汇总时间和截止前提醒。安装与测试见 [手机版说明](android/README.md)。

## 图形界面

双击 `课业日程.exe` 打开软件。EXE 包含 Python 运行时，扫描使用本机的 Microsoft Edge。EXE 旁的 `.state` 目录保存任务快照。使用源码时也可以双击 `启动课业日程.cmd`。

在左侧「平台连接」找到学习通或中国大学 MOOC，点「账号」，输入账号密码后点「保存」。学习通使用手机号或超星号，中国大学 MOOC 使用手机号或邮箱。软件将账号密码存入当前 Windows 用户的凭据管理器，下一轮扫描自动使用。

保存前可点「验证登录」，用当前输入尝试登录平台官网，结果会显示在窗口中。验证本身不会保存账号密码。更新密码时再次打开「账号」并输入新密码，也可在同一窗口点「清除密码」。

左侧「平台连接」显示已保存账号的密码登录状态。验证通过后保存同一组账号密码，状态会立即更新。修改账号密码后会重新等待验证。每轮自动扫描也会更新该状态。点击状态可看到上次扫描的结果。

也可以在项目目录运行下面的命令打开软件。

```powershell
uv run --locked python -m homework_scan gui
```

界面默认显示未到期任务，点「全部任务」可查看各状态的任务。已完成的考试归入「已截止」。开始日期距今满一年的任务在界面中隐藏。左侧切换平台，右上可搜课程或任务，也可按作业、测验和考试筛选。单击任务行或「任务 ↗」可在系统默认浏览器打开该项作业、测验或考试。平台未提供可确认的任务入口时，点击「列表 ↗」可打开对应列表。浏览器需要登录相应平台。

界面打开后默认立即扫描，随后每轮结束后等待 15 分钟再扫。可取消勾选暂停定时扫描，或随时点「立即扫描」。需要在官网手动验证时，点对应平台的「登录」，完成验证后保持浏览器打开，回到界面点「验证并扫描」。

## 构建 Windows EXE

在 Windows x64 的项目目录运行以下命令，生成 `课业日程.exe`。双击 EXE 即可启动。通过终端启动时，在 EXE 所在目录运行，运行库会临时解包到该目录的 `.state\bundle`。

```powershell
uv run --locked --group package pyinstaller --noconfirm --onefile --hide-console hide-early --runtime-tmpdir '.state\bundle' --name 课业日程 --distpath . --specpath build --workpath build homework_scan_launcher.py
```

## 命令行用法

在项目目录打开 PowerShell，需要 uv 和已安装的 Edge。也可以在 `login`、`scan`、`watch` 命令中加 `--browser chrome`。

```powershell
uv sync --locked
uv run --locked python -m homework_scan credentials set --platform chaoxing
uv run --locked python -m homework_scan credentials set --platform icourse163
uv run --locked python -m homework_scan scan
uv run --locked python -m homework_scan list
```

`credentials set` 会提示输入账号和密码。学习通使用手机号或超星号，中国大学 MOOC 使用手机号或邮箱。密码输入时不显示，账号和密码保存在当前 Windows 用户的凭据管理器。已有账号设置在下一轮生效。用 `credentials clear --platform <平台>` 删除该平台的账号密码。

每轮自动扫描都会在空白浏览器会话中用账号密码登录对应平台官网。新会话仅用于当轮扫描，扫描结束后关闭。缺少账号密码或登录失败时，扫描器会记录对应平台的状态并保留已有任务。

### 手动登录并扫描

平台要求扫码或验证码时，可通过 `login` 在官网手动完成验证。登录后保持浏览器窗口打开，回到终端按 Enter，程序会验证并用该会话扫描一次。

```powershell
uv run --locked python -m homework_scan login --platform chaoxing
uv run --locked python -m homework_scan login --platform icourse163
```

## 定时扫描

```powershell
uv run --locked python -m homework_scan watch --interval-minutes 15
```

启动时扫描一次，每轮完成后等待指定时间再扫描。默认间隔 15 分钟，保持终端进程运行即可，按 Ctrl+C 停止。`scan` 和 `watch` 也支持 `--platform chaoxing` 或 `--platform icourse163`。

## 扫描范围和数据

- 检查学习通「我学的课」及其课程文件夹，以及中国大学 MOOC 个人中心的 MOOC、SPOC 课程。已结束和已归档的课程排除在扫描范围外。尚未开放的课程计入发现数，并在后续轮次重新检查。
- `platforms` 中的 `course_count` 是发现的范围内课程数，`scanned_course_count` 是任务列表完整读取的课程数，`unopened_course_count` 是尚未开放的课程数。课程入口或任务列表无法核对时，`issues` 会说明位置。
- 采集列表提供的标题、时间和状态。学习通列表没有日期或只显示作业倒计时时，补读页面反映的作答区间、考试起止时间或未开放提示中的开始时间。作答区间省略当年年份时，使用页面响应的北京时间补齐。只有倒计时可用时，截止时间标为估算。无法确认的截止时间显示为「截止时间未知」。时间统一为北京时间。MOOC 作业使用提交截止时间。
- 本地快照为 `.state/tasks.json`。`platforms` 记录每个平台的本轮状态及上次完整扫描时间，`courses` 为按时间排列的课程组。每组以 `course_name` 为标题，`tasks` 是按截止时间排序的任务。没有截止时间时使用已知的开始时间，无时间的记录排在后面。
- 登录过期、网络失败或列表无法确认时保留原有记录。部分成功的扫描同时保留新读到的任务及未能确认的旧记录，并显示具体失败位置。
- 扫描访问课程、任务列表，以及学习通缺少时间的任务详情。详情通过 GET 读取页面的时间字段，页面脚本不会运行。

## 界面数据

| 界面字段 | 数据 |
| --- | --- |
| 课程名称 | `course_name` |
| 任务标题 | `title` |
| 截止日期 | `due_at`，精度由 `due_precision` 标记 |
| 剩余时间 | 按当前时间计算。`list --json` 提供当时的 `remaining` 和 `remaining_as_of` |
| 点击跳转 | `open_url` 是浏览器打开的地址，`open_scope` 标记为任务、列表或课程。旧记录会在下次成功扫描时更新 |

图形界面可以用 `due_at` 持续刷新倒计时。只有截止日期时显示剩余天数。平台未提供截止时间时保留空值。

## 平台接口

中国大学 MOOC 的开放接口需要平台分配的应用凭据和相应授权，公开文档包含课程、学习进度和考核统计。项目尚未取得可读取个人作业和考试截止时间的开放接口授权。[接入凭据](https://docs.icourse163.org/api-doc/general-info.html) · [数据接口](https://docs.icourse163.org/api-doc/get-info.html)

超星开放平台提供应用 OAuth 和令牌刷新。申请应用后仍需确认接口权限是否覆盖学生作业和考试。当前扫描使用学生网页登录后可访问的列表。[超星对接流程](https://auth.open.chaoxing.com/open/doc/list/yluywps0/zpsakobd/u58aqj3r)

## 本地数据与许可证

Windows 版账号密码保存在 Windows 凭据管理器中。Android 版使用 Android Keystore 加密保存在应用私有目录。Git 忽略 `.state/`、本地导出、会话文件、日志和签名密钥。提交改动时请使用虚构的账号和课程数据。

项目代码采用 [MIT 许可证](LICENSE)。Gradle Wrapper 采用 [Apache License 2.0](android/gradle/wrapper/LICENSE)。
