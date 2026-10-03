# 简云plus (JianYun) - Android 手机上的全能 Web 服务端

![Version](https://img.shields.io/badge/version-1.2-blue)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)
![targetSdk](https://img.shields.io/badge/targetSdk-34-brightgreen)

**简云plus** 是一款运行在 Android 设备上的服务端管理工具。无需 ROOT，即可将手机/平板变为功能完整的 Web 服务器，集成 Nginx、PHP、MariaDB、Redis、FTP、WebSocket 和内网穿透，适合开发者调试、学习建站、内网服务搭建等场景。

---

## 功能特性

- **📊 智能仪表盘** — 实时监控内存/存储/网络流量，组件运行状态一目了然，内外网 IP 自动获取
- **⚙️ 四合一 Web 服务** — Nginx + PHP + MariaDB + Redis 独立开关，支持自动启动
- **📥 组件下载中心** — 内置下载管理器，支持多版本选择和断点续传
- **📁 网站管理** — 添加/删除站点，自动生成 PHP 演示页，端口冲突检测
- **📡 FTP 文件服务** — 基于 Apache FtpServer，多用户管理，公网/内网访问可控
- **🔗 WebSocket 服务端** — Token 认证、IP 封禁系统、连接限速、消息缓冲区、客户端管理
- **🌐 内网穿透 (cpolar)** — 一键暴露本地服务到公网，支持国内/海外多区域
- **🖥️ phpMyAdmin** — 浏览器中管理 MariaDB 数据库
- **🎨 10 套主题** — Slate / Blue / Green / Purple / Orange / Red / Cyan / Pink / Indigo / Brown
- **🔔 权限引导** — 文件管理 → 电池白名单 → 通知权限，分步完成
- **🔄 应用内更新** — 远程检查版本，支持 changelog 展示和强制更新

---

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| Web 服务器 | Nginx | 1.26.2 |
| 脚本解析 | PHP | 8.5.1 / 8.3.0 / 7.4.0 |
| 数据库 | MariaDB | 12.3.2 |
| 缓存/队列 | Redis | 8.8.0 |
| DB 管理面板 | phpMyAdmin | 5.2.1 |
| FTP 服务 | Apache FtpServer (ftpserver-core 1.2.0) | — |
| WebSocket | Java-WebSocket 1.5.6 + Mina 2.1.6 | — |
| 内网穿透 | cpolar | — |
| 统计 | 友盟 (umeng-common 9.6.2 + asms 1.8.0) | — |
| UI | AndroidX + Material Design + Navigation Component | — |

---

## 组件版本

- **Nginx**: 1.26.2
- **PHP**: 8.5.1 / 8.3.0 / 7.4.0（三版本可选）
- **MariaDB**: 12.3.2
- **Redis**: 8.8.0
- **phpMyAdmin**: 5.2.1

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 网络通信，访问公网 IP |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 后台持续运行服务器服务 |
| `POST_NOTIFICATIONS` | 显示运行状态通知 |
| `MANAGE_EXTERNAL_STORAGE` | 读取/写入网站文件、数据库文件 |
| `READ_EXTERNAL_STORAGE` | 兼容旧版本的文件读取 |
| `REQUEST_INSTALL_PACKAGES` | 安装更新 APK |
| **电池白名单**（需手动授权） | 防止系统在后台杀死服务器进程 |

**隐私声明**：简云plus仅访问 App 指定目录的文件，不上传不收集任何个人信息。友盟统计仅用于基础的版本分布和使用情况分析。

---

## 快速开始

1. **下载安装** 简云plus APK
2. **授权权限**：文件管理权限 → 电池白名单 → 通知权限（App 内有引导）
3. **下载组件**：进入「组件」页，下载所需服务（Nginx + PHP 为建站必需）
4. **添加网站**：进入「设置」→ 网站管理，添加网站名称和端口
5. **启动服务**：返回仪表盘，打开对应组件的开关
6. **访问网站**：浏览器打开 `http://手机IP:端口` 即可查看

---

## 开发构建

```bash
# 克隆项目
git clone <repo-url>
cd AndroidServerBuild

# 使用 Android Studio 打开项目，或命令行构建：
./gradlew assembleRelease

# APK 输出位置：
# app/build/outputs/apk/release/app-release.apk
```

签名配置（仅供维护者参考）：
- 密钥文件：`keys/zm.jks`
- 别名：`jyzm`

---

## 免责声明

简云plus仅供学习、开发和合法用途使用。用户需遵守当地法律法规，不得将本应用用于任何非法目的，包括但不限于搭建违法网站、传播违法信息、侵犯他人权益等。使用本应用所产生的任何法律后果由用户自行承担。

---

## 交流与反馈

- **QQ 群**: 634288136

---

## 开源许可

本项目所依赖的第三方库均为开源软件，完整许可声明见应用内「开源许可声明」页面。

- Apache FtpServer — Apache 2.0
- Java-WebSocket — MIT
- Mina — Apache 2.0
- SLF4J — MIT
- AndroidX — Apache 2.0
- Material Components — Apache 2.0
