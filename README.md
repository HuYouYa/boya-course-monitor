# 博雅课程监控

这是一个本地运行的博雅选课监控器。用户只需要在页面填写博雅账号密码、每周每天的空闲时间段和通知方式；程序会登录博雅选课系统，区分课程的开课时间与预选时间，在课程时间完全落入空闲时间且预选窗口已开放时发送通知。上课形式分为线上和线下；选择线下后再选择学院路或沙河校区。

## 启动

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m playwright install chromium
python -m uvicorn app:app --host 127.0.0.1 --port 8787
```

打开 <http://127.0.0.1:8787>。账号密码和 SMTP 密码以本机 `.secret` 加密后写入 `data/boya.sqlite3`，不会通过接口返回明文。浏览器登录状态保存在 `data/browser-state.json`，删除它即可重新登录。

## 通知配置

普通邮箱和 QQ 邮箱都通过 SMTP 发送。QQ/163 邮箱通常要求使用邮箱设置中生成的授权码；例如 QQ SMTP 主机为 `smtp.qq.com`，端口 `465`，启用 SSL。SMTP 用户名应填写完整邮箱地址；发件人字段可以填写完整邮箱，也可以填写显示名称（此时程序会使用 SMTP 用户名作为地址）。可以先点击“发送测试通知”。

空闲时间输入支持多段，例如周一填写 `09:00-12:00, 14:00-18:00`。课程人数会从课程表中抓取，并显示为“已选人数 / 总人数”，同时写入通知邮件。

监控默认每 5 分钟轮询一次。首次发现新课程，或课程的开课/预选时间、状态、地点、分类、教师、已选人数等字段发生变化时发送一封通知；没有变化的轮询不会重复发送。通知失败会保留待通知状态，在下一轮重试。轮询间隔可通过环境变量 `BOYA_POLL_INTERVAL` 设置，单位为秒，最小 60 秒。

部署到服务器时建议使用单进程运行，避免多个进程同时轮询并重复发信：

```powershell
$env:BOYA_POLL_INTERVAL = "60"
python -m uvicorn app:app --host 0.0.0.0 --port 8787 --workers 1
```

请持久化 `data/` 和 `.secret`，其中包含登录状态、课程记录和本机加密密钥。

## Android 手机端（独立运行）

手机端 APK 内置配置、监控服务、课程筛选、课程指纹和 SMTP 通知，不需要电脑或 Python 服务。首次打开后填写博雅账号、每周空闲时间、校区和邮箱配置，点击“打开博雅登录”在 APK 内完成一次统一认证，再点击“启动监控”。应用会以 Android 前台服务定时检查，更新课程时发送通知。

已构建的调试版 APK：[`mobile/releases/boya-course-monitor.apk`](mobile/releases/boya-course-monitor.apk)。账号、密码、SMTP 授权码和课程记录保存在 Android 本地，密码使用 Android Keystore 加密。

手机端源码在 `mobile/`。如需重新构建，使用 Android SDK 35、Build Tools 35.0.0 和 Java 17，运行 `mobile/build-apk.ps1` 即可重新产出 APK。

## 运行边界

程序只读取课程并发提醒，不会自动替用户点击选课。课程列表抓取依赖博雅网站的登录状态和页面结构；如果学校统一认证增加验证码或二次认证，需要在浏览器状态文件中先完成一次人工登录。

## 验证

```powershell
python -m unittest discover -s tests -v
```
