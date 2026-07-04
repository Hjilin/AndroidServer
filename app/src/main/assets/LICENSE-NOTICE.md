# 开源许可声明 / Open Source Licenses

本应用（AndroidServer）使用以下开源组件及第三方软件。各组件的版权归原作者所有，使用遵循对应许可协议。

---

## 一、Android 应用层（Gradle 依赖）

### AndroidX AppCompat 1.6.1
- 来源: https://developer.android.com/jetpack/androidx/releases/appcompat
- 许可: Apache License 2.0
- 版权: The Android Open Source Project

### Google Material Components 1.11.0
- 来源: https://github.com/material-components/material-components-android
- 许可: Apache License 2.0
- 版权: Google LLC

### AndroidX Navigation 2.7.7 (fragment / ui)
- 来源: https://developer.android.com/jetpack/androidx/releases/navigation
- 许可: Apache License 2.0
- 版权: The Android Open Source Project

### Apache FtpServer 1.2.0 (ftpserver-core)
- 来源: https://mina.apache.org/ftpserver/
- 许可: Apache License 2.0
- 版权: The Apache Software Foundation

### Apache MINA 2.1.6 (mina-core)
- 来源: https://mina.apache.org/
- 许可: Apache License 2.0
- 版权: The Apache Software Foundation

### SLF4J Android 1.7.36
- 来源: https://www.slf4j.org/
- 许可: MIT License
- 版权: QOS.ch

### desugar_jdk_libs 2.1.5
- 来源: https://developer.android.com/studio/write/java11-default-support-table
- 许可: Apache License 2.0
- 版权: The Android Open Source Project

---

## 二、运行于 Android 设备上的服务端二进制（GPL / 自由软件）

应用通过下载安装包的方式将下列服务部署到本地 `bin/<component>/<version>/` 目录运行。
这些组件随组件压缩包分发，**保留其原始许可与版权声明**。

### PHP 8.3.0 / 8.5.1 (php-cgi)
- 来源: https://www.php.net/
- 许可: PHP License 3.01
- 版权: The PHP Group
- 备注: 包含 SG11 (SourceGuardian) 加载器为闭源商业组件,需另行遵守 SourceGuardian EULA。

### Nginx 1.26.2
- 来源: https://nginx.org/
- 许可: BSD 2-Clause License
- 版权: Igor Sysoev, Nginx, Inc.

### MariaDB 12.3.2 (mariadbd)
- 来源: https://mariadb.org/
- 许可: GNU General Public License v2 (GPLv2)
- 版权: MariaDB Foundation 及贡献者
- 备注: **GPL 传染性提示** — 本应用作为客户端使用 MariaDB,不修改其源码,通过进程间调用其官方二进制实现数据库服务,未将 MariaDB 代码链接进本 APK。

### Redis 8.8.0 (redis-server)
- 来源: https://redis.io/
- 许可: RSALv2 / SSPLv1 Dual License
- 版权: Redis Labs / Salvatore Sanfilippo
- 备注: 商业/生产部署需遵守 SSPL;本应用以独立进程方式调用,未修改其源码。

---

## 三、闭源 / 商业组件

### SourceGuardian Loader (ixed.8.3.lin / ixed.8.5.lin → sg11.so)
- 来源: https://www.sourceguardian.com/
- 许可: SourceGuardian Loader License (商业 / 闭源)
- 版权: SourceGuardian Ltd.
- 备注: 随 PHP 8.3/8.5 压缩包分发;只用于解码受 SourceGuardian 保护的 PHP 文件。

---

## 四、相关说明

1. **许可文件保留** — 各组件压缩包内均保留原始 `LICENSE` / `NOTICE` 文件,部署到 `bin/<component>/<version>/` 后可读取。

2. **不修改上游源码** — 所有服务端组件以官方原版二进制形式部署,本应用**不修改、不链接**这些组件的源码,仅作为**独立进程的控制器**与**客户端**使用。

3. **动态加载隔离** — GPL 组件(如 MariaDB)与本应用运行在独立进程,通过 TCP / Unix socket 通信,符合 GPL "mere aggregation" 条款。

4. **完整许可文本** — 各许可协议全文可在以下地址查阅:
   - Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0
   - MIT: https://opensource.org/licenses/MIT
   - BSD-2: https://opensource.org/licenses/BSD-2-Clause
   - GPLv2: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
   - PHP 3.01: https://www.php.net/license/3_01.txt
   - SSPLv1: https://www.mongodb.com/licensing/server-side-public-license

5. **联系方式** — 如对许可合规有疑问,请联系应用作者。

---

最后更新: 2025
