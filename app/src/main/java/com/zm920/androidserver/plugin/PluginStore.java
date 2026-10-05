package com.zm920.androidserver.plugin;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件市场（在线清单）。每个条目描述一个可从远程下载的插件。
 * download_url 指向资源仓库的 zip 插件包。
 */
public class PluginStore {
    // 资源仓库下载前缀（v1.1.5：新增 openlist 4.2.6 插件）
    private static final String BASE = "https://gh-proxy.com/https://github.com/Hjilin/mobileserver-bin-resources/releases/download/v1.1.5/";

    public static class StoreItem {
        public String id;
        public String name;
        public String version;
        public String desc;
        public String downloadUrl;

        StoreItem(String id, String name, String version, String desc, String file) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.desc = desc;
            this.downloadUrl = BASE + file;
        }
    }

    public static List<StoreItem> list() {
        List<StoreItem> items = new ArrayList<>();
        items.add(new StoreItem(
                "openlist",
                "OpenList 网盘",
                "4.2.6",
                "开源网盘挂载服务，多网盘挂载(阿里/夸克/OneDrive等)+WebDAV+网页管理",
                "plugin-openlist.zip"));
        items.add(new StoreItem(
                "copyparty",
                "copyparty 网盘",
                "1.0.0",
                "多协议网盘文件服务器（HTTP/WebDAV/FTP/SMB），自带网页上传/预览/播放",
                "plugin-copyparty.zip"));
        items.add(new StoreItem(
                "ddns-go",
                "DDNS 动态域名",
                "1.0.0",
                "自动把本机公网 IP 同步到域名解析，实现外网访问",
                "plugin-ddns.zip"));
        items.add(new StoreItem(
                "filebrowser",
                "FileBrowser 文件管理",
                "1.0.0",
                "轻量网页文件管理器，上传/下载/预览/分享",
                "plugin-filebrowser.zip"));
        return items;
    }
}
