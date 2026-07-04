package com.zm920.androidserver.ui;

import android.util.Log;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.media.MediaScannerConnection;
import java.io.IOException;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.zm920.androidserver.ui.widget.ToastUtil;
import com.zm920.androidserver.util.PermissionUtil;
import com.zm920.androidserver.util.StoragePaths;
import com.zm920.androidserver.util.SiteScanner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import androidx.fragment.app.Fragment;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.navigation.fragment.NavHostFragment;
import com.zm920.androidserver.MainActivity;
import com.zm920.androidserver.R;
import com.zm920.androidserver.download.BinaryDownloader;
import com.zm920.androidserver.server.WebServer;
import com.zm920.androidserver.server.BinaryDeployer;
import com.zm920.androidserver.server.DatabaseManager;
import com.zm920.androidserver.server.StartupLogger;
import com.zm920.androidserver.service.ProcessManager;
import com.zm920.androidserver.config.ConfigGenerator;
import com.zm920.androidserver.ui.DashboardFragment;
import com.zm920.androidserver.cpolar.CpolarConfig;
import com.zm920.androidserver.cpolar.CpolarManager;
import com.zm920.androidserver.service.CpolarService;
import com.zm920.androidserver.service.MefrpService;
import com.zm920.androidserver.service.WsServerService;
import com.zm920.androidserver.update.UpdateChecker;
import com.zm920.androidserver.update.UpdateInstaller;
import com.google.android.material.switchmaterial.SwitchMaterial;


import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class SettingsFragment extends Fragment {

    private android.content.BroadcastReceiver cpolarStatusReceiver;
    private android.content.BroadcastReceiver mefrpStatusReceiver;
    private android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final String PREFS = "server_settings";
    private static final String KEY_SITES = "sites";
    // 交流群按钮隐藏开关，false/true 则不在设置页显示
    private static final boolean SHOW_QQ_GROUP_BUTTON = false;
    private static final String QQ_GROUP_NUMBER = "634288136";

    private SharedPreferences prefs;
    private EditText etMysqlPort, etRedisPort, etFtpPort, etWsPort;
    private TextView tvDataDir, tvFtpUserCount;
    private com.google.android.material.switchmaterial.SwitchMaterial switchFtpWan;
    private com.google.android.material.switchmaterial.SwitchMaterial switchMysqlWan;
    private com.google.android.material.switchmaterial.SwitchMaterial switchWsWan;
    private TextView tvWsToken, btnWsCopyToken, btnWsRegenToken;

    private BinaryDownloader downloader;
    private File appDir;
    private ActivityResultLauncher<Intent> dirPickerLauncher;

    // 主题按钮
    private TextView themeSlate, themeBlue, themeGreen, themePurple, themeOrange,
            themeRed, themeCyan, themePink, themeIndigo, themeBrown;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences(PREFS, 0);
        appDir = new File(requireContext().getFilesDir(), "bin");
        downloader = new BinaryDownloader(appDir);

        dirPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == getActivity().RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            String parentPath = resolveSafUri(treeUri);
                            if (parentPath != null && !parentPath.isEmpty()) {
                                String newDataDir = parentPath + "/wwwroot/www";
                                String defaultDir = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www";
                                String cur = prefs.getString("data_dir", defaultDir);
                                if (cur.equals(newDataDir)) {
                                    ToastUtil.showShort(getContext(), "已经是该位置");
                                    return;
                                }
                                // 走迁移流程
                                confirmMigrate(cur, newDataDir);
                            }
                        }
                    }
                });

        // 主题选择器
        themeSlate = view.findViewById(R.id.theme_slate);
        themeBlue = view.findViewById(R.id.theme_blue);
        themeGreen = view.findViewById(R.id.theme_green);
        themePurple = view.findViewById(R.id.theme_purple);
        themeOrange = view.findViewById(R.id.theme_orange);
        themeRed = view.findViewById(R.id.theme_red);
        themeCyan = view.findViewById(R.id.theme_cyan);
        themePink = view.findViewById(R.id.theme_pink);
        themeIndigo = view.findViewById(R.id.theme_indigo);
        themeBrown = view.findViewById(R.id.theme_brown);

        setThemeButtonColor(themeSlate, R.color.theme_slate_primary);
        setThemeButtonColor(themeBlue, R.color.blue_primary);
        setThemeButtonColor(themeGreen, R.color.theme_green_primary);
        setThemeButtonColor(themePurple, R.color.theme_purple_primary);
        setThemeButtonColor(themeOrange, R.color.theme_orange_primary);
        setThemeButtonColor(themeRed, R.color.theme_red_primary);
        setThemeButtonColor(themeCyan, R.color.theme_cyan_primary);
        setThemeButtonColor(themePink, R.color.theme_pink_primary);
        setThemeButtonColor(themeIndigo, R.color.theme_indigo_primary);
        setThemeButtonColor(themeBrown, R.color.theme_brown_primary);

        highlightCurrentTheme();

        themeSlate.setOnClickListener(v -> switchTheme("slate"));
        themeBlue.setOnClickListener(v -> switchTheme("blue"));
        themeGreen.setOnClickListener(v -> switchTheme("green"));
        themePurple.setOnClickListener(v -> switchTheme("purple"));
        themeOrange.setOnClickListener(v -> switchTheme("orange"));
        themeRed.setOnClickListener(v -> switchTheme("red"));
        themeCyan.setOnClickListener(v -> switchTheme("cyan"));
        themePink.setOnClickListener(v -> switchTheme("pink"));
        themeIndigo.setOnClickListener(v -> switchTheme("indigo"));
        themeBrown.setOnClickListener(v -> switchTheme("brown"));

        // 端口
        etMysqlPort = view.findViewById(R.id.et_mysql_port);
        etRedisPort = view.findViewById(R.id.et_redis_port);
        etFtpPort = view.findViewById(R.id.et_ftp_port);
        etWsPort = view.findViewById(R.id.et_ws_port);
        tvFtpUserCount = view.findViewById(R.id.tv_ftp_user_count);
        switchFtpWan = view.findViewById(R.id.switch_ftp_wan);
        switchMysqlWan = view.findViewById(R.id.switch_mysql_wan);
        switchWsWan = view.findViewById(R.id.switch_ws_wan);
        tvWsToken = view.findViewById(R.id.tv_ws_token);
        btnWsCopyToken = view.findViewById(R.id.btn_ws_copy_token);
        btnWsRegenToken = view.findViewById(R.id.btn_ws_regen_token);
        // 公网开关
        if (switchWsWan != null) {
            switchWsWan.setChecked(WsServerService.getBindWan(requireContext()));
            switchWsWan.setOnCheckedChangeListener((btn, checked) -> {
                if (!btn.isPressed()) return;
                WsServerService.setBindWan(requireContext(), checked);
                String msg = checked
                        ? "下次启动将监听 0.0.0.0（请确认已开启公网开关并重启服务）"
                        : "下次启动将仅监听 127.0.0.1（更安全，需重启服务生效）";
                ToastUtil.showLong(getContext(), msg);
            });
        }
        // 加载 token
        if (tvWsToken != null) {
            tvWsToken.setText(WsServerService.getToken(requireContext()));
        }
        if (btnWsCopyToken != null) {
            btnWsCopyToken.setOnClickListener(v -> {
                try {
                    String t = WsServerService.getToken(requireContext());
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ws_token", t));
                        ToastUtil.showShort(getContext(), "Token 已复制");
                    }
                } catch (Exception e) {
                    ToastUtil.showShort(getContext(), "复制失败: " + e.getMessage());
                }
            });
        }
        if (btnWsRegenToken != null) {
            btnWsRegenToken.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("重新生成 Token？")
                        .setMessage("生成后正在连接的客户端会被踢下线，需用新 URL 重新连接。\n是否继续？")
                        .setPositiveButton("生成", (d, w) -> {
                            String t = WsServerService.regenerateToken(requireContext());
                            if (tvWsToken != null) tvWsToken.setText(t);
                            ToastUtil.showLong(getContext(), "Token 已更新（重启服务生效）\n" + t);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }
        loadPorts();
        view.findViewById(R.id.btn_manage_ftp_users).setOnClickListener(v -> showFtpUserManagerSheet());
        refreshFtpUserCount();
        loadFtpWanSwitch();
        loadMysqlWanSwitch();
        applyThemeToSwitches();

        // 网站管理
        view.findViewById(R.id.btn_manage_sites).setOnClickListener(v -> showSiteManagerSheet());



        view.findViewById(R.id.btn_permission_settings).setOnClickListener(v -> showPermissionDialog());

        // 数据目录
        tvDataDir = view.findViewById(R.id.tv_data_dir);
        String dir = prefs.getString("data_dir", android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www");
        tvDataDir.setText(dir);
        view.findViewById(R.id.btn_change_dir).setOnClickListener(v -> showDirDialog());

        // 内网穿透（二级窗口：cpolar + ME Frp）
        view.findViewById(R.id.btn_tunnel).setOnClickListener(v -> showTunnelBottomSheet());
        refreshTunnelStatus();

        // 关于简云
        view.findViewById(R.id.btn_about_jianyun).setOnClickListener(v -> {
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .hide(this)
                    .add(R.id.nav_host_fragment, new com.zm920.androidserver.ui.AboutJianyunFragment(), "aboutJianyun")
                    .addToBackStack(null)
                    .commit();
        });
        view.findViewById(R.id.btn_about_licenses).setOnClickListener(v -> showLicensesDialog());

        // 交流群
        View btnQqGroup = view.findViewById(R.id.btn_qq_group);
        if (btnQqGroup != null) {
            btnQqGroup.setVisibility(SHOW_QQ_GROUP_BUTTON ? View.VISIBLE : View.GONE);
            btnQqGroup.setOnClickListener(v -> {
                String qqNum = QQ_GROUP_NUMBER;
                String qqPkg = "com.tencent.mobileqq";

                // 方式1：mqqopensdkapi 协议拉起 QQ 加群（最可靠）
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    intent.setData(android.net.Uri.parse(
                            "mqqopensdkapi://bizAgent/qm/qr?url=https%3A%2F%2Fqm.qq.com%2Fq%2FB4LJ2RQq1E"));
                    intent.setPackage(qqPkg);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.getContext().startActivity(intent);
                    return;
                } catch (Exception ignored) {}

                // 方式2：不指定包名再试一次 mqqopensdkapi
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=https%3A%2F%2Fqm.qq.com%2Fq%2FB4LJ2RQq1E"));
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.getContext().startActivity(intent);
                    return;
                } catch (Exception ignored) {}

                // 方式3：浏览器打开 qm.qq.com 加群页面
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://qm.qq.com/q/B4LJ2RQq1E"));
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.getContext().startActivity(intent);
                    return;
                } catch (Exception ignored) {}

                // 兜底：复制群号到剪贴板
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("qq_group", qqNum));
                    }
                } catch (Exception ignored) {}
                com.zm920.androidserver.ui.widget.ToastUtil.showLong(
                        requireContext(), "QQ 群号 " + qqNum + " 已复制，请打开 QQ 手动搜索加入");
            });
        }

        // 检查更新
        view.findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate());

        // 把所有跳转/管理文字染成主题色
        applyThemeToActionLabels();

        // 注册 cpolar 状态广播
        registerCpolarReceiver();
        // 注册 mefrp 状态广播
        registerMefrpReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 切回时立即刷新一次
        refreshTunnelStatus();
        // 从设置页返回时，检查是否有已下载的 APK 等待安装
        if (UpdateInstaller.tryInstallPending(requireContext())) {
            ToastUtil.showShort(requireContext(), "权限已获取，开始安装...");
        }
    }

    private void registerCpolarReceiver() {
        if (cpolarStatusReceiver != null) return;
        cpolarStatusReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) return;
                if (!isAdded() || isDetached() || getView() == null) return;
                refreshTunnelStatus();
                // 弹窗可能开着，也让它刷新
                refreshOpenCpolarSheet(
                    intent.getStringExtra(CpolarService.EXTRA_STATUS),
                    intent.getStringExtra(CpolarService.EXTRA_URL));
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(CpolarService.ACTION_STATUS);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(cpolarStatusReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
        } else {
            requireContext().registerReceiver(cpolarStatusReceiver, filter);
        }
    }

    private void registerMefrpReceiver() {
        if (mefrpStatusReceiver != null) return;
        mefrpStatusReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) return;
                if (!isAdded() || isDetached() || getView() == null) return;
                refreshTunnelStatus();
                // 弹窗可能开着，也让它刷新
                refreshOpenMefrpSheet(
                    intent.getStringExtra(com.zm920.androidserver.service.MefrpService.EXTRA_STATUS),
                    intent.getStringExtra(com.zm920.androidserver.service.MefrpService.EXTRA_URL));
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(
                com.zm920.androidserver.service.MefrpService.ACTION_STATUS);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(mefrpStatusReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
        } else {
            requireContext().registerReceiver(mefrpStatusReceiver, filter);
        }
    }

    private void applyThemeToActionLabels() {
        int c = getThemeAccentColor();
        int[] ids = {R.id.tv_action_sites, R.id.tv_action_ftp_users,
                R.id.tv_action_permission, R.id.tv_action_licenses, R.id.tv_action_tunnel,
                R.id.tv_action_update, R.id.tv_action_jianyun, R.id.tv_action_qq_group};
        for (int id : ids) {
            android.view.View v = getView() == null ? null : getView().findViewById(id);
            if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(c);
        }
        if (getView() != null) {
            android.view.View vd = getView().findViewById(R.id.btn_change_dir);
            if (vd instanceof android.widget.TextView) ((android.widget.TextView) vd).setTextColor(c);
        }
    }
    // ---- 网站管理 BottomSheet ----

    private int getThemeAccentColor() {
        String themeKey = prefs.getString("theme_key", "slate");
        switch (themeKey) {
            case "green": return ContextCompat.getColor(requireContext(), R.color.theme_green_primary);
            case "purple": return ContextCompat.getColor(requireContext(), R.color.theme_purple_primary);
            case "orange": return ContextCompat.getColor(requireContext(), R.color.theme_orange_primary);
            case "blue": return ContextCompat.getColor(requireContext(), R.color.blue_primary);
            case "red": return ContextCompat.getColor(requireContext(), R.color.theme_red_primary);
            case "cyan": return ContextCompat.getColor(requireContext(), R.color.theme_cyan_primary);
            case "pink": return ContextCompat.getColor(requireContext(), R.color.theme_pink_primary);
            case "indigo": return ContextCompat.getColor(requireContext(), R.color.theme_indigo_primary);
            case "brown": return ContextCompat.getColor(requireContext(), R.color.theme_brown_primary);
            default: return ContextCompat.getColor(requireContext(), R.color.theme_slate_primary);
        }
    }

    private int lightenColor(int color, float fraction) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = (int) (r + (255 - r) * fraction);
        g = (int) (g + (255 - g) * fraction);
        b = (int) (b + (255 - b) * fraction);
        return Color.rgb(r, g, b);
    }

    private void showSiteManagerSheet() {
        // 扫描 data_dir，自动注册未在列表中的网站
        SiteScanner.scanAndRegister(requireContext().getApplicationContext(), prefs);

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        // 标题行
        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp2px(16));

        TextView title = new TextView(requireContext());
        title.setText("网站管理");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        headerRow.addView(title);

        // 添加按钮
        TextView addBtn = new TextView(requireContext());
        addBtn.setText("+ 添加");
        addBtn.setTextSize(13f);
        addBtn.setTextColor(getThemeAccentColor());
        addBtn.setClickable(true);
        addBtn.setFocusable(true);
        GradientDrawable addBg = new GradientDrawable();
        addBg.setShape(GradientDrawable.RECTANGLE);
        addBg.setCornerRadius(dp2px(8));
        addBg.setColor(lightenColor(getThemeAccentColor(), 0.92f));
        addBg.setStroke(dp2px(1), lightenColor(getThemeAccentColor(), 0.7f));
        addBtn.setBackground(addBg);
        addBtn.setPadding(dp2px(14), dp2px(6), dp2px(14), dp2px(6));
        addBtn.setOnClickListener(v -> {
            sheet.dismiss();
            showAddSiteBottomSheet();
        });
        headerRow.addView(addBtn);

        root.addView(headerRow);

        // 网站列表
        Set<String> siteSet = prefs.getStringSet(KEY_SITES, new HashSet<>());

        if (siteSet.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("暂未添加网站");
            empty.setTextSize(14f);
            empty.setTextColor(0xFF9AA0A6);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp2px(24), 0, dp2px(24));
            root.addView(empty);
        } else {
            for (String site : siteSet) {
                String[] p = site.split("\\|");
                String name = p.length > 0 ? p[0] : site;
                String port = p.length > 1 ? p[1] : "8080";

                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setShape(GradientDrawable.RECTANGLE);
                rowBg.setCornerRadius(dp2px(12));
                rowBg.setColor(0xFFF7F8FA);
                rowBg.setStroke(dp2px(1), 0xFFE2E6EA);
                row.setBackground(rowBg);

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp2px(8);
                row.setLayoutParams(rowLp);

                // 左侧蓝色竖线
                View indicator = new View(requireContext());
                LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(dp2px(3), LinearLayout.LayoutParams.MATCH_PARENT);
                indicatorLp.setMarginEnd(dp2px(12));
                indicator.setLayoutParams(indicatorLp);
                GradientDrawable indBg = new GradientDrawable();
                indBg.setShape(GradientDrawable.RECTANGLE);
                indBg.setCornerRadius(dp2px(2));
                indBg.setColor(getThemeAccentColor());
                indicator.setBackground(indBg);
                row.addView(indicator);

                // 名称
                TextView nameTv = new TextView(requireContext());
                nameTv.setText(name);
                nameTv.setTextSize(15f);
                nameTv.setTextColor(0xFF202124);
                nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
                nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                row.addView(nameTv);

                // 端口标签
                TextView portTv = new TextView(requireContext());
                portTv.setText("端口:" + port);
                portTv.setTextSize(12f);
                portTv.setTextColor(0xFF1A73E8);
                GradientDrawable portBg = new GradientDrawable();
                portBg.setShape(GradientDrawable.RECTANGLE);
                portBg.setCornerRadius(dp2px(6));
                portBg.setColor(0xFFE8F0FE);
                portTv.setBackground(portBg);
                portTv.setPadding(dp2px(8), dp2px(3), dp2px(8), dp2px(3));
                LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                portLp.setMarginEnd(dp2px(10));
                portTv.setLayoutParams(portLp);
                row.addView(portTv);

                // 删除按钮
                TextView delBtn = new TextView(requireContext());
                delBtn.setText("删除");
                delBtn.setTextSize(12f);
                delBtn.setTextColor(0xFFEA4335);
                delBtn.setClickable(true);
                delBtn.setFocusable(true);
                GradientDrawable delBg = new GradientDrawable();
                delBg.setShape(GradientDrawable.RECTANGLE);
                delBg.setCornerRadius(dp2px(8));
                delBg.setColor(0xFFFDECEC);
                delBtn.setBackground(delBg);
                delBtn.setPadding(dp2px(10), dp2px(4), dp2px(10), dp2px(4));
                final String key = site;
                delBtn.setOnClickListener(v -> {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("删除网站")
                            .setMessage("确定要删除 \"" + key.split("\\|")[0] + "\" 吗？")
                            .setPositiveButton("删除", (d, w) -> {
                                androidx.appcompat.app.AlertDialog progress = newThemeProgressDialog("删除中", "正在删除网站，请稍候...").show();
                                new Thread(() -> {
                                    try {
                                        removeSite(key);
                                    } finally {
                                        if (getActivity() != null) {
                                            getActivity().runOnUiThread(() -> {
                                                try { progress.dismiss(); } catch (Exception ignored) {}
                                                try { sheet.dismiss(); } catch (Exception ignored) {}
                                            });
                                        }
                                    }
                                }).start();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
                row.addView(delBtn);

                root.addView(row);
            }
        }

        ScrollView siteScroll = new ScrollView(requireContext());
        siteScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        siteScroll.setFillViewport(true);
        siteScroll.addView(root);
        sheet.setContentView(siteScroll);
        sheet.setOnShowListener(d -> {
            android.widget.FrameLayout bottomSheet = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        sheet.show();
    }

    // ---- 添加网站 BottomSheet ----

    private void showAddSiteBottomSheet() {
        boolean nginxInstalled = downloader.getInstalledVersion("Nginx") != null
                && !downloader.getInstalledVersion("Nginx").isEmpty();
        boolean phpInstalled = downloader.getInstalledVersion("PHP") != null
                && !downloader.getInstalledVersion("PHP").isEmpty();

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        // 标题
        TextView title = new TextView(requireContext());
        title.setText("添加网站");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp2px(16));
        root.addView(title);

        // 环境检测
        LinearLayout envRow = new LinearLayout(requireContext());
        envRow.setOrientation(LinearLayout.HORIZONTAL);
        envRow.setGravity(Gravity.CENTER_VERTICAL);
        envRow.setPadding(0, 0, 0, dp2px(12));

        TextView envLabel = new TextView(requireContext());
        envLabel.setText("环境检测  ");
        envLabel.setTextSize(12f);
        envLabel.setTextColor(0xFF9AA0A6);
        envRow.addView(envLabel);

        TextView nginxTag = new TextView(requireContext());
        nginxTag.setText(nginxInstalled ? "Nginx ✓" : "Nginx ✗");
        nginxTag.setTextSize(11f);
        nginxTag.setTextColor(nginxInstalled ? 0xFF34A853 : 0xFFEA4335);
        GradientDrawable nginxBg = new GradientDrawable();
        nginxBg.setShape(GradientDrawable.RECTANGLE);
        nginxBg.setCornerRadius(dp2px(6));
        nginxBg.setColor(nginxInstalled ? 0xFFE8F5E9 : 0xFFFDECEC);
        nginxTag.setBackground(nginxBg);
        nginxTag.setPadding(dp2px(8), dp2px(3), dp2px(8), dp2px(3));
        envRow.addView(nginxTag);

        View envSpacer = new View(requireContext());
        envSpacer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(8), 1));
        envRow.addView(envSpacer);

        TextView phpTag = new TextView(requireContext());
        phpTag.setText(phpInstalled ? "PHP ✓" : "PHP ✗");
        phpTag.setTextSize(11f);
        phpTag.setTextColor(phpInstalled ? 0xFF34A853 : 0xFFEA4335);
        GradientDrawable phpBg = new GradientDrawable();
        phpBg.setShape(GradientDrawable.RECTANGLE);
        phpBg.setCornerRadius(dp2px(6));
        phpBg.setColor(phpInstalled ? 0xFFE8F5E9 : 0xFFFDECEC);
        phpTag.setBackground(phpBg);
        phpTag.setPadding(dp2px(8), dp2px(3), dp2px(8), dp2px(3));
        envRow.addView(phpTag);

        root.addView(envRow);

        // 网站名称
        TextView nameLabel = new TextView(requireContext());
        nameLabel.setText("网站名称");
        nameLabel.setTextSize(12f);
        nameLabel.setTextColor(0xFF5F6368);
        nameLabel.setPadding(0, dp2px(8), 0, dp2px(4));
        root.addView(nameLabel);

        EditText inputName = new EditText(requireContext());
        inputName.setHint("例如 myblog");
        inputName.setText("demo");
        inputName.setTextSize(14f);
        inputName.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        inputName.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputName.setSingleLine(true);
        root.addView(inputName);

        // 监听端口
        TextView portLabel = new TextView(requireContext());
        portLabel.setText("监听端口");
        portLabel.setTextSize(12f);
        portLabel.setTextColor(0xFF5F6368);
        portLabel.setPadding(0, dp2px(12), 0, dp2px(4));
        root.addView(portLabel);

        EditText inputPort = new EditText(requireContext());
        inputPort.setHint("例如 8080");
        inputPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        inputPort.setTextSize(14f);
        inputPort.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        inputPort.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputPort.setSingleLine(true);
        root.addView(inputPort);


        // 保存按钮
        TextView addBtn = new TextView(requireContext());
        addBtn.setText("添加网站");
        addBtn.setTextSize(15f);
        addBtn.setTextColor(0xFFFFFFFF);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setClickable(true);
        addBtn.setFocusable(true);
        int btnPadV = dp2px(12);
        addBtn.setPadding(0, btnPadV, 0, btnPadV);
        GradientDrawable addBtnBg = new GradientDrawable();
        addBtnBg.setShape(GradientDrawable.RECTANGLE);
        addBtnBg.setCornerRadius(dp2px(10));
        addBtnBg.setColor(getThemeAccentColor());
        addBtn.setBackground(addBtnBg);
        LinearLayout.LayoutParams addBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addBtnLp.topMargin = dp2px(20);
        addBtn.setLayoutParams(addBtnLp);
        root.addView(addBtn);

        addBtn.setOnClickListener(v -> {
            String name = inputName.getText().toString().trim();
            String port = inputPort.getText().toString().trim();
            if (name.isEmpty() || port.isEmpty()) {
                ToastUtil.showShort(getContext(), "网站名称和端口不能为空");
                return;
            }
            if (!nginxInstalled) {
                ToastUtil.showShort(getContext(), "请先在「组件」页下载 Nginx");
                return;
            }
            if (!phpInstalled) {
                ToastUtil.showShort(getContext(), "请先在「组件」页下载 PHP");
                return;
            }

            // 端口合法性
            int portNum;
            try {
                portNum = Integer.parseInt(port);
                if (portNum <= 0 || portNum > 65535) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                ToastUtil.showShort(getContext(), "端口必须为 1-65535 的数字");
                return;
            }

            Set<String> existing = prefs.getStringSet(KEY_SITES, new HashSet<>());
            for (String site : existing) {
                String[] sp = site.split("\\|");
                if (sp.length >= 2) {
                    if (sp[0].equals(name)) {
                        ToastUtil.showShort(getContext(), "网站名称 '" + name + "' 已存在");
                        return;
                    }
                    if (sp[1].equals(port)) {
                        ToastUtil.showShort(getContext(), "端口 " + port + " 已被 '" + sp[0] + "' 占用");
                        return;
                    }
                }
            }

            // 端口是否被本机其他进程占用
            com.zm920.androidserver.network.PortUtil.Result pr =
                    com.zm920.androidserver.network.PortUtil.check(portNum);
            if (!pr.available) {
                String detail = isPortUsedByOwnService(portNum)
                        ? "已被本应用其他服务占用（" + detectOwnServiceOnPort(portNum) + "）"
                        : "已被其他应用占用";
                ToastUtil.showLong(getContext(), "端口 " + port + " " + detail + "，请更换端口");
                return;
            }

            saveSite(name, port, "", "");
            createDemoPage(name, port);
            restartNginx();
            sheet.dismiss();
            ToastUtil.showShort(getContext(), "网站已添加，演示页已生成");
        });

        // 用 ScrollView 包裹，确保内容可滚动
        ScrollView addScroll = new ScrollView(requireContext());
        addScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        // 不设 setFillViewport(true)，让内容自然滚动
        addScroll.addView(root);
        sheet.setContentView(addScroll);
        sheet.setOnShowListener(d -> {
            android.widget.FrameLayout bottomSheet = sheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<android.widget.FrameLayout> behavior =
                        BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            sheet.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        sheet.show();
    }

    // ---- 主题 ----

    private void setThemeButtonColor(TextView btn, int colorRes) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(0);
        drawable.setColor(ContextCompat.getColor(requireContext(), colorRes));
        btn.setBackground(drawable);
    }

    private void highlightCurrentTheme() {
        String current = prefs.getString("theme_key", "slate");

        applyButtonColor(themeSlate, R.color.theme_slate_primary);
        applyButtonColor(themeBlue, R.color.blue_primary);
        applyButtonColor(themeGreen, R.color.theme_green_primary);
        applyButtonColor(themePurple, R.color.theme_purple_primary);
        applyButtonColor(themeOrange, R.color.theme_orange_primary);
        applyButtonColor(themeRed, R.color.theme_red_primary);
        applyButtonColor(themeCyan, R.color.theme_cyan_primary);
        applyButtonColor(themePink, R.color.theme_pink_primary);
        applyButtonColor(themeIndigo, R.color.theme_indigo_primary);
        applyButtonColor(themeBrown, R.color.theme_brown_primary);

        TextView target;
        int colorRes;
        switch (current) {
            case "blue":
                target = themeBlue;
                colorRes = R.color.blue_primary;
                break;
            case "green":
                target = themeGreen;
                colorRes = R.color.theme_green_primary;
                break;
            case "purple":
                target = themePurple;
                colorRes = R.color.theme_purple_primary;
                break;
            case "orange":
                target = themeOrange;
                colorRes = R.color.theme_orange_primary;
                break;
            case "red":
                target = themeRed;
                colorRes = R.color.theme_red_primary;
                break;
            case "cyan":
                target = themeCyan;
                colorRes = R.color.theme_cyan_primary;
                break;
            case "pink":
                target = themePink;
                colorRes = R.color.theme_pink_primary;
                break;
            case "indigo":
                target = themeIndigo;
                colorRes = R.color.theme_indigo_primary;
                break;
            case "brown":
                target = themeBrown;
                colorRes = R.color.theme_brown_primary;
                break;
            default:
                target = themeSlate;
                colorRes = R.color.theme_slate_primary;
                break;
        }

        GradientDrawable active = new GradientDrawable();
        active.setShape(GradientDrawable.RECTANGLE);
        active.setCornerRadius(0);
        active.setColor(ContextCompat.getColor(requireContext(), colorRes));
        active.setStroke(3, android.graphics.Color.WHITE);
        target.setBackground(active);
    }

    private void applyButtonColor(TextView btn, int colorRes) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(0);
        normal.setColor(ContextCompat.getColor(requireContext(), colorRes));
        btn.setBackground(normal);
    }

    private void switchTheme(String themeKey) {
        prefs.edit().putString("theme_key", themeKey).apply();

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyTheme(themeKey);
        }

        highlightCurrentTheme();
        applyThemeToSwitches();
        ToastUtil.showShort(getContext(), "主题已切换");

        try {
            if (getActivity() != null) {
                Fragment df = getParentFragmentManager().findFragmentByTag("dashboard");
                if (df instanceof DashboardFragment) {
                    ((DashboardFragment) df).refreshTheme();
                }
            }
        } catch (Exception e) {
            Log.e("Settings", "refreshDashboardTheme failed", e);
        }
    }

    private void showPermissionDialog() {
        String[] items = new String[]{"手动申请文件管理权限", "申请电池白名单", "申请通知栏权限"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("权限设置")
                .setItems(items, (d, which) -> {
                    if (!(getActivity() instanceof MainActivity)) return;
                    MainActivity activity = (MainActivity) getActivity();
                    switch (which) {
                        case 0:
                            activity.doRequestStoragePermission();
                            break;
                        case 1:
                            activity.requestBatteryOptimizationWhitelist();
                            break;
                        case 2:
                            activity.requestNotificationPermission();
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            savePorts();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        savePorts();
        // 之前在 onPause 里注销 cpolarStatusReceiver 会导致切到其他 Tab 时
        // 收不到 CPOLAR_STATUS 广播、状态永远停留在离开时的样子，需要切回窗口才更新。
        // 改为只在 onDestroyView 注销，保证 Settings Fragment 存活期间 broadcast 都能回调。
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 注销 cpolar 广播（从 onPause 移到这里）
        if (cpolarStatusReceiver != null) {
            try { requireContext().unregisterReceiver(cpolarStatusReceiver); } catch (Exception ignored) {}
            cpolarStatusReceiver = null;
        }
        // 注销 mefrp 广播
        if (mefrpStatusReceiver != null) {
            try { requireContext().unregisterReceiver(mefrpStatusReceiver); } catch (Exception ignored) {}
            mefrpStatusReceiver = null;
        }
        // 同时清理 BottomSheet 引用，避免泄漏 Activity
        clearCpolarSheetRefs();
        clearMefrpSheetRefs();
    }

    // ---- 端口 ----

    private void loadPorts() {
        etMysqlPort.setText(String.valueOf(prefs.getInt("mysql_port", 3306)));
        etRedisPort.setText(String.valueOf(prefs.getInt("redis_port", 6379)));
        etFtpPort.setText(String.valueOf(prefs.getInt("ftp_port", 2121)));
        etWsPort.setText(String.valueOf(WsServerService.getConfiguredPort(requireContext())));
    }

    private void refreshFtpUserCount() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("ftp_users", "[]"));
            if (tvFtpUserCount != null) tvFtpUserCount.setText(arr.length() + " 个");
        } catch (Exception e) {
            if (tvFtpUserCount != null) tvFtpUserCount.setText("0 个");
        }
    }

    private void loadFtpWanSwitch() {
        if (switchFtpWan == null) return;
        switchFtpWan.setChecked(prefs.getBoolean("ftp_bind_wan", false));
        switchFtpWan.setOnCheckedChangeListener((btn, checked) -> {
            if (!btn.isPressed()) return;
            prefs.edit().putBoolean("ftp_bind_wan", checked).apply();
            ToastUtil.showShort(getContext(), checked ? "将允许外网访问（重启 FTP 后生效）" : "将限制为仅本机访问（重启 FTP 后生效）");
        });
    }

    private void loadMysqlWanSwitch() {
        if (switchMysqlWan == null) return;
        switchMysqlWan.setChecked(prefs.getBoolean("mysql_bind_wan", false));
        switchMysqlWan.setOnCheckedChangeListener((btn, checked) -> {
            if (!btn.isPressed()) return;
            prefs.edit().putBoolean("mysql_bind_wan", checked).apply();
            ToastUtil.showShort(getContext(), checked ? "MariaDB 下次启动将监听 0.0.0.0（允许外部访问）" : "MariaDB 下次启动将仅监听 127.0.0.1（仅本机）");
        });
    }


    private void savePorts() {
        try {
            int ftp = Integer.parseInt(etFtpPort.getText().toString());
            if (ftp < 1 || ftp > 65535) ftp = 2121;
            prefs.edit()
                    .putInt("mysql_port", Integer.parseInt(etMysqlPort.getText().toString()))
                    .putInt("redis_port", Integer.parseInt(etRedisPort.getText().toString()))
                    .putInt("ftp_port", ftp)
                    .putInt("ws_port", parsePort(etWsPort, 8080))
                    .apply();
        } catch (NumberFormatException ignored) {}
    }

    private int parsePort(EditText et, int defVal) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            if (v < 1 || v > 65535) return defVal;
            return v;
        } catch (Exception e) {
            return defVal;
        }
    }

    // ---- 网站数据 ----

    private void createDemoPage(String siteName, String port) {
        if (!PermissionUtil.ensureStoragePermission(requireActivity())) {
            ToastUtil.showLong(getContext(), "请先在设置页授权「文件管理权限」后再添加网站");
            return;
        }
        try {
            String dataDir = prefs.getString("data_dir", android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www");
            String siteRoot = dataDir + "/" + siteName;
            File siteDirF = new File(siteRoot);
            siteDirF.mkdirs();
            // 写入网站元数据（用于卸载重装后恢复）
            int portNum;
            try { portNum = Integer.parseInt(port); } catch (Exception ex) { portNum = 8080; }
            SiteScanner.writeMeta(siteDirF, portNum, "");
            SiteScanner.ensureDataDirMarker(dataDir);
            // 放置 .nomedia 防止系统相册扫描网站图片
            File nomediaFile = new File(siteRoot, ".nomedia");
            if (!nomediaFile.exists()) {
                try { nomediaFile.createNewFile(); } catch (IOException e) { Log.e("Settings", "创建 .nomedia 失败", e); }
            }
            MediaScannerConnection.scanFile(requireContext(), new String[]{nomediaFile.getAbsolutePath()}, null, (path, uri) -> {});

            String html = "<?php $pv = phpversion(); ?>\n<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n"
                + "<title>" + siteName + " - \u7b80\u4e91\u6f14\u793a\u7ad9\u70b9</title>\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">\n"
                + "<style>\n"
                + "*{margin:0;padding:0;box-sizing:border-box}\n"
                + "body{font-family:'Inter','Segoe UI','-apple-system',sans-serif;\n"
                + "min-height:100vh;background:#f5f7fb;color:#333;display:flex;\n"
                + "align-items:center;justify-content:center;padding:20px;}\n"
                + ".card{background:#fff;border-radius:20px;padding:40px 36px;\n"
                + "max-width:420px;width:100%;text-align:center;\n"
                + "box-shadow:0 2px 12px rgba(0,0,0,0.06),0 8px 32px rgba(0,0,0,0.04);\n"
                + "border:1px solid rgba(0,0,0,0.04);}\n"
                + ".top-bar{display:flex;align-items:center;gap:6px;margin-bottom:24px;\n"
                + "padding-bottom:14px;border-bottom:1px solid #eee;}\n"
                + ".dot{width:10px;height:10px;border-radius:50%;}\n"
                + ".dot.r{background:#ff5f56;}\n"
                + ".dot.y{background:#ffbd2e;}\n"
                + ".dot.g{background:#27c93f;}\n"
                + ".top-bar-label{margin-left:auto;font-size:11px;color:#aaa;\n"
                + "font-family:monospace;}\n"
                + ".header{margin-bottom:24px;}\n"
                + "h1{font-size:22px;font-weight:700;color:#1a1a2e;margin-bottom:4px;}\n"
                + ".subtitle{font-size:13px;color:#999;}\n"
                + ".status{display:inline-block;margin:14px 0 20px;\n"
                + "padding:5px 20px;border-radius:20px;font-size:12px;font-weight:600;\n"
                + "background:#e8f5e9;border:1px solid #c8e6c9;color:#2e7d32;}\n"
                + ".status::before{content:\"\u25cf \";font-size:10px;}\n"
                + ".info{display:flex;justify-content:center;gap:30px;\n"
                + "padding:18px 0;margin-bottom:20px;\n"
                + "background:#f8f9fc;border-radius:12px;}\n"
                + ".info-item{text-align:center;}\n"
                + ".info-item .label{font-size:10px;color:#aaa;text-transform:uppercase;\n"
                + "letter-spacing:1px;margin-bottom:4px;}\n"
                + ".info-item .value{font-size:15px;color:#1a1a2e;font-weight:600;\n"
                + "font-family:monospace;}\n"
                + ".footer{padding-top:16px;border-top:1px solid #eee;\n"
                + "font-size:11px;color:#bbb;display:flex;justify-content:space-between;}\n"
                + ".footer .ver{font-family:monospace;color:#ccc;}\n"
                + "</style></head><body>\n"
                + "<div class=\"card\">\n"
                + "<div class=\"top-bar\">\n"
                + "<span class=\"dot r\"></span><span class=\"dot y\"></span><span class=\"dot g\"></span>\n"
                + "<span class=\"top-bar-label\">server@android</span>\n"
                + "</div>\n"
                + "<div class=\"header\">\n"
                + "<h1>" + siteName + "</h1>\n"
                + "<div class=\"subtitle\">简云 - Android 服务端</div>\n"
                + "</div>\n"
                + "<div class=\"status\">\u8fd0\u884c\u4e2d</div>\n"
                + "<div class=\"info\">\n"
                + "<div class=\"info-item\"><div class=\"label\">\u7aef\u53e3</div>\n"
                + "<div class=\"value\">" + port + "</div></div>\n"
                + "<div class=\"info-item\"><div class=\"label\">\u670d\u52a1\u5668</div>\n"
                + "<div class=\"value\">Nginx</div></div>\n"
                + "<div class=\"info-item\"><div class=\"label\">\u5e73\u53f0</div>\n"
                + "<div class=\"value\">Android</div></div>\n"
                + "<div class=\"info-item\"><div class=\"label\">PHP</div>\n"
                + "<div class=\"value\"><?php echo $pv; ?></div></div>\n"
                + "</div>\n"
                + "<div class=\"footer\">\n"
                + "<span>\u00a9 简云 v1.0.0</span>\n"
                + "<span class=\"ver\">" + siteName + "</span>\n"
                + "</div></div>\n"
                + "</body></html>";

            File indexFile = new File(siteRoot, "index.php");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(indexFile)) {
                fos.write(html.getBytes("UTF-8"));
                fos.flush();
            }
            indexFile.setReadable(true, false);
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg == null) errMsg = "未知错误";
            android.util.Log.e("Settings", "生成演示页失败", e);
            ToastUtil.showLong(getContext(), "生成失败: " + errMsg);
        }
    }

    /** 检查某端口是否被本 app 自己的服务占用（通过 Prefs 配置和运行状态判断） */
    private boolean isPortUsedByOwnService(int port) {
        try {
            SharedPreferences sp = requireContext().getSharedPreferences("server_settings", 0);
            // Web server (Nginx) 端口：KEY 未明确，按 sites 列表动态看（这里只看自己已添加的 sites）
            // 实际上启动服务时端口来自配置，这里查常见服务的 port 配置
            if (sp.getInt("nginx_port", -1) == port) return true;
            if (sp.getInt("mysql_port", 3306) == port) return true;
            if (sp.getInt("redis_port", 6379) == port) return true;
            if (sp.getInt("ftp_port", 2121) == port) return true;
            if (sp.getInt("ws_port", 8080) == port) return true;
            if (sp.getInt("cpolar_webui_port", 9200) == port) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private String detectOwnServiceOnPort(int port) {
        try {
            SharedPreferences sp = requireContext().getSharedPreferences("server_settings", 0);
            if (sp.getInt("nginx_port", -1) == port) return "Nginx";
            if (sp.getInt("mysql_port", 3306) == port) return "MariaDB";
            if (sp.getInt("redis_port", 6379) == port) return "Redis";
            if (sp.getInt("ftp_port", 2121) == port) return "FTP";
            if (sp.getInt("ws_port", 8080) == port) return "WebSocket";
            if (sp.getInt("cpolar_webui_port", 9200) == port) return "cpolar";
        } catch (Exception ignored) {}
        return "本应用其他服务";
    }

    private void saveSite(String name, String port, String root, String rewrite) {
        Set<String> sites = new HashSet<>(prefs.getStringSet(KEY_SITES, new HashSet<>()));
        String encodedRewrite = rewrite == null ? "" : rewrite.replace("|", "\u0001");
        sites.add(name + "|" + port + "|" + (root == null ? "" : root) + "|" + encodedRewrite);
        prefs.edit().putStringSet(KEY_SITES, sites).apply();
        // 同步写入网站元数据（用于卸载重装后恢复）
        try {
            String dataDir = prefs.getString("data_dir", StoragePaths.getDefaultWwwRoot());
            File siteDirF = new File(dataDir, name);
            int portNum;
            try { portNum = Integer.parseInt(port); } catch (Exception ex) { portNum = 8080; }
            SiteScanner.writeMeta(siteDirF, portNum, rewrite == null ? "" : rewrite);
            SiteScanner.ensureDataDirMarker(dataDir);
        } catch (Exception ignored) {}
        // 添加网站后标记跳过下一次 nginx 自动启动
        prefs.edit().putBoolean("skip_nginx_auto_start", true).apply();
    }

    private void removeSite(String key) {
        Set<String> sites = new HashSet<>(prefs.getStringSet(KEY_SITES, new HashSet<>()));
        sites.remove(key);
        prefs.edit().putStringSet(KEY_SITES, sites).apply();

        try {
            String[] parts = key.split("\\|");
            if (parts.length >= 1) {
                String name = parts[0];
                String dataDir = prefs.getString("data_dir", StoragePaths.getDefaultWwwRoot());
                File siteDir = new File(dataDir, name);
                if (siteDir.exists()) {
                    SiteScanner.deleteMeta(siteDir);
                    deleteDir(siteDir);
                    Log.i("Settings", "已删除网站目录: " + siteDir.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.e("Settings", "清理网站文件失败", e);
        }

        try {
            String siteName = key.split("\\|")[0];
            String dbName = siteName.replaceAll("[^a-zA-Z0-9_]", "_");
            Log.i("Settings", "如需删除数据库，请手动执行: DROP DATABASE IF EXISTS `" + dbName + "`");
        } catch (Exception ignored) {}

        restartNginx();
    }

    // ---- 数据目录 ----

    private void showDirDialog() {
        String defaultDir = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www";
        String current = prefs.getString("data_dir", defaultDir);
        tvDataDir.setText(current);

        String hint = "当前网站文件存储位置:\n" + current
                + "\n\n点击「选择目录」可更改存储位置（选择的是父目录，实际数据目录会自动追加 /wwwroot/www）。"
                + "\n\n默认位置: " + defaultDir;

        new ThemedDialogBuilder(requireContext())
                .setTitle("网站存储位置")
                .setMessage(hint)
                .addButton("选择目录", true, d -> {
                    d.dismiss();
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    dirPickerLauncher.launch(intent);
                })
                .addButton("恢复默认", false, d -> {
                    d.dismiss();
                    String cur = prefs.getString("data_dir", defaultDir);
                    if (cur.equals(defaultDir)) {
                        ToastUtil.showShort(getContext(), "已经是默认位置");
                        return;
                    }
                    confirmMigrate(cur, defaultDir);
                })
                .addButton("关闭", false, d -> d.dismiss())
                .show();
    }

    /**
     * 确认迁移：旧 -> 新
     */
    private void confirmMigrate(String oldDir, String newDir) {
        File oldF = new File(oldDir);
        File newF = new File(newDir);
        boolean oldExists = oldF.exists() && oldF.isDirectory();
        boolean oldHasContent = oldExists && (oldF.list() != null && oldF.list().length > 0);
        boolean newExists = newF.exists() && newF.isDirectory();
        boolean newHasContent = newExists && (newF.list() != null && newF.list().length > 0);

        String msg = "从:\n" + oldDir + "\n\n到:\n" + newDir;
        if (oldHasContent) {
            msg += "\n\n旧目录有网站文件，将自动迁移到新目录。";
        } else {
            msg += "\n\n旧目录为空或不存在，将直接创建新目录。";
        }
        if (newHasContent) {
            msg += "\n\n注意：新目录已有内容，迁移时会保留新目录原有文件。";
        }

        new ThemedDialogBuilder(requireContext())
                .setTitle("迁移数据目录")
                .setMessage(msg)
                .addButton("开始迁移", true, d -> {
                    d.dismiss();
                    doMigrate(oldDir, newDir);
                })
                .addButton("取消", false, d -> d.dismiss())
                .show();
    }

    /**
     * 执行迁移：复制旧目录内容到新目录，成功后删除旧目录
     */
    private void doMigrate(String oldDir, String newDir) {
        // 进度对话框
        final android.app.Dialog progressDialog = new android.app.Dialog(requireContext());
        progressDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        progressDialog.setCancelable(false);

        android.widget.LinearLayout pRoot = new android.widget.LinearLayout(requireContext());
        pRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
        pRoot.setBackgroundColor(0xFFFFFFFF);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        pRoot.setPadding(pad, pad, pad, pad);

        android.widget.TextView pTitle = new android.widget.TextView(requireContext());
        pTitle.setText("正在迁移数据目录...");
        pTitle.setTextSize(15f);
        pTitle.setTextColor(0xFF202124);
        pTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.LinearLayout.LayoutParams ptlp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        ptlp.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
        pTitle.setLayoutParams(ptlp);
        pRoot.addView(pTitle);

        final android.widget.TextView pMsg = new android.widget.TextView(requireContext());
        pMsg.setText("准备中...");
        pMsg.setTextSize(13f);
        pMsg.setTextColor(0xFF5F6368);
        pRoot.addView(pMsg);

        progressDialog.setContentView(pRoot);
        android.view.Window pWin = progressDialog.getWindow();
        if (pWin != null) {
            pWin.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.85f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            android.graphics.drawable.GradientDrawable pBg = new android.graphics.drawable.GradientDrawable();
            pBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            pBg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
            pBg.setColor(0xFFFFFFFF);
            pWin.setBackgroundDrawable(pBg);
        }
        progressDialog.show();

        final String oldDirF = oldDir;
        final String newDirF = newDir;

        new Thread(() -> {
            try {
                File oldF = new File(oldDirF);
                File newF = new File(newDirF);

                // 1. 创建新目录
                if (!newF.exists() && !newF.mkdirs()) {
                    throw new Exception("无法创建新目录: " + newDirF);
                }

                // 2. 复制旧目录内容到新目录
                if (oldF.exists() && oldF.isDirectory()) {
                    final int[] fileCount = {0};
                    copyDirRecursive(oldF, newF, () -> {
                        fileCount[0]++;
                        final int cnt = fileCount[0];
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (pMsg != null) pMsg.setText("已迁移 " + cnt + " 个文件/目录...");
                            });
                        }
                    });
                }

                // 3. 删除旧目录
                if (oldF.exists()) {
                    deleteDir(oldF);
                }

                // 3.5 迁移基础设施目录（备份目录、日志目录）到新基础设施根
                String oldInfraRoot = StoragePaths.getInfraRoot(oldDirF);
                String newInfraRoot = StoragePaths.getInfraRoot(newDirF);
                if (!oldInfraRoot.equals(newInfraRoot)) {
                    String[] infraSubdirs = {"备份目录", "log"};
                    for (String sub : infraSubdirs) {
                        File oldSub = new File(oldInfraRoot, sub);
                        File newSub = new File(newInfraRoot, sub);
                        if (oldSub.exists() && oldSub.isDirectory()) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (pMsg != null) pMsg.setText("正在迁移 " + sub + "...");
                                });
                            }
                            newSub.getParentFile().mkdirs();
                            copyDirRecursive(oldSub, newSub, null);
                            deleteDir(oldSub);
                        }
                    }
                }

                // 4. 更新 data_dir
                prefs.edit().putString("data_dir", newDirF).apply();
                SiteScanner.ensureDataDirMarker(newDirF);

                // 4.5 扫描新目录，自动注册原有网站到 sites
                int beforeCount = prefs.getStringSet(KEY_SITES, new HashSet<>()).size();
                SiteScanner.scanAndRegister(requireContext().getApplicationContext(), prefs);
                int afterCount = prefs.getStringSet(KEY_SITES, new HashSet<>()).size();
                final int addedCount = afterCount - beforeCount;

                // 5. 关闭进度框，提示重启 nginx
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try { progressDialog.dismiss(); } catch (Exception ignored) {}
                        if (tvDataDir != null) tvDataDir.setText(newDirF);
                        showMigrateSuccessDialog(newDirF, addedCount);
                    });
                }
            } catch (Exception e) {
                Log.e("Settings", "迁移失败", e);
                final String errMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try { progressDialog.dismiss(); } catch (Exception ignored) {}
                        showMigrateFailDialog(errMsg);
                    });
                }
            }
        }).start();
    }

    /**
     * 递归复制目录
     */
    private void copyDirRecursive(File src, File dst, Runnable onEach) throws Exception {
        if (!src.exists()) return;
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new Exception("无法创建目录: " + dst.getAbsolutePath());
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyDirRecursive(c, new File(dst, c.getName()), onEach);
                    if (onEach != null) onEach.run();
                }
            }
        } else {
            // 复制单个文件
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 迁移成功提示
     */
    private void showMigrateSuccessDialog(String newDir, int addedCount) {
        String msg = "数据目录已迁移到:\n" + newDir;
        if (addedCount > 0) {
            msg += "\n\n已自动添加 " + addedCount + " 个新目录中的网站。";
        }
        msg += "\n\n请重启 nginx 以应用新路径（仪表盘关闭 nginx 后再开启）。";
        new ThemedDialogBuilder(requireContext())
                .setTitle("迁移完成")
                .setMessage(msg)
                .addButton("立即重启", true, d -> {
                    d.dismiss();
                    restartNginx();
                })
                .addButton("稍后手动", false, d -> d.dismiss())
                .show();
    }

    /**
     * 迁移失败提示
     */
    private void showMigrateFailDialog(String errMsg) {
        new ThemedDialogBuilder(requireContext())
                .setTitle("迁移失败")
                .setMessage("错误信息:\n" + errMsg
                        + "\n\ndata_dir 未修改，请检查新路径权限或磁盘空间后重试。")
                .addButton("关闭", true, d -> d.dismiss())
                .show();
    }

    private String resolveSafUri(Uri treeUri) {
        try {
            String path = treeUri.getPath();
            if (path == null) return treeUri.toString();
            int treeIdx = path.indexOf("/tree/");
            if (treeIdx < 0) return treeUri.toString();
            String afterTree = path.substring(treeIdx + 6);
            int docIdx = afterTree.indexOf("/document/");
            if (docIdx >= 0) afterTree = afterTree.substring(0, docIdx);
            String decoded = java.net.URLDecoder.decode(afterTree, "UTF-8");
            if (decoded.startsWith("primary:")) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + decoded.substring("primary:".length());
            }
            if (decoded.startsWith("primary/")) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + decoded.substring("primary/".length());
            }
            if ("primary".equals(decoded)) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            }
            if (decoded.contains(":")) {
                return "/storage/" + decoded.replace(":", "/");
            }
            return "/storage/" + decoded;
        } catch (Exception e) {
            Log.e("Settings", "resolveSafUri failed", e);
            return treeUri.toString();
        }
    }

    private void showLicensesDialog() {
        String text;
        try {
            java.io.InputStream is = requireContext().getAssets().open("LICENSE-NOTICE.md");
            int sz = is.available();
            byte[] buf = new byte[sz];
            int read = 0;
            while (read < sz) {
                int n = is.read(buf, read, sz - read);
                if (n < 0) break;
                read += n;
            }
            text = new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            text = "无法加载许可声明文件: " + e.getMessage();
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(requireContext());
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF202124);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16));
        tv.setTextIsSelectable(true);
        sc.addView(tv);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("开源许可声明")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) deleteDir(c);
            }
        }
        dir.delete();
    }

    private void restartNginx() {
        com.zm920.androidserver.server.WebServer ws = com.zm920.androidserver.MainActivity.webServerRef;
        if (ws != null && ws.isRunning()) {
            int port = prefs.getInt("nginx_port", -1);
            new Thread(() -> {
                try {
                    ws.stop();
                    // 轮询端口释放，100ms 间隔，最多等 2s
                    if (port > 0) {
                        for (int i = 0; i < 20; i++) {
                            com.zm920.androidserver.network.PortUtil.Result pr =
                                    com.zm920.androidserver.network.PortUtil.check(port);
                            if (pr.available) break;
                            Thread.sleep(100);
                        }
                    }
                    ws.start();
                    Log.i("Settings", "nginx 已重启");
                } catch (Exception e) {
                    Log.e("Settings", "nginx 重启失败", e);
                }
            }).start();
        }
    }

    private void checkUpdate() {
        final TextView tvStatus = getView() == null ? null : getView().findViewById(R.id.tv_update_status);
        if (tvStatus != null) tvStatus.setText("检查中...");
        final TextView tvAction = getView() == null ? null : getView().findViewById(R.id.tv_action_update);
        if (tvAction != null) tvAction.setVisibility(View.GONE);

        UpdateChecker.check(requireContext(), new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info) {
                if (tvStatus != null) tvStatus.setText("点击检查");
                if (tvAction != null) tvAction.setVisibility(View.VISIBLE);

                int localCode = UpdateChecker.getLocalVersionCode(requireContext());
                if (info.versionCode > localCode) {
                    showUpdateDialog(info);
                } else {
                    ToastUtil.showShort(requireContext(), "已是最新版本");
                }
            }

            @Override
            public void onError(String msg) {
                if (tvStatus != null) tvStatus.setText("点击检查");
                if (tvAction != null) tvAction.setVisibility(View.VISIBLE);
                ToastUtil.showShort(requireContext(), "检查失败: " + msg);
            }
        });
    }

    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        int accentColor = getThemeAccentColor();
        int r = Color.red(accentColor);
        int g = Color.green(accentColor);
        int b = Color.blue(accentColor);
        float density = getResources().getDisplayMetrics().density;

        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle(null);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(24));

        // 标题
        TextView title = new TextView(requireContext());
        title.setText("发现新版本");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = dp2px(4);
        title.setLayoutParams(tl);
        root.addView(title);

        // 版本信息
        TextView tvVer = new TextView(requireContext());
        tvVer.setText("v" + info.versionName + "  ·  " + info.releaseDate + "  ·  " + info.fileSize);
        tvVer.setTextSize(12f);
        tvVer.setTextColor(0xFF5F6368);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
        vl.bottomMargin = dp2px(16);
        tvVer.setLayoutParams(vl);
        root.addView(tvVer);

        // 更新内容
        if (info.changelog != null && !info.changelog.isEmpty()) {
            TextView clTitle = new TextView(requireContext());
            clTitle.setText("更新内容");
            clTitle.setTextSize(13f);
            clTitle.setTextColor(0xFF202124);
            clTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams cltl = new LinearLayout.LayoutParams(-1, -2);
            cltl.bottomMargin = dp2px(8);
            clTitle.setLayoutParams(cltl);
            root.addView(clTitle);

            for (String item : info.changelog) {
                LinearLayout itemRow = new LinearLayout(requireContext());
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setPadding(dp2px(4), dp2px(2), 0, dp2px(2));

                TextView dot = new TextView(requireContext());
                dot.setText("·");
                dot.setTextSize(13f);
                dot.setTextColor(0xFF5F6368);
                dot.setPadding(0, 0, dp2px(8), 0);
                itemRow.addView(dot);

                TextView tvItem = new TextView(requireContext());
                tvItem.setText(item);
                tvItem.setTextSize(13f);
                tvItem.setTextColor(0xFF3C4043);
                tvItem.setLineSpacing(0f, 1.3f);
                itemRow.addView(tvItem);

                root.addView(itemRow);
            }
        }

        // 强制更新提示
        if (info.forceUpdate) {
            TextView forceHint = new TextView(requireContext());
            forceHint.setText("此版本为强制更新，请下载后安装");
            forceHint.setTextSize(12f);
            forceHint.setTextColor(0xFFD93025);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
            fl.topMargin = dp2px(12);
            fl.bottomMargin = dp2px(4);
            forceHint.setLayoutParams(fl);
            root.addView(forceHint);
        }

        // 按钮行
        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp2px(20);
        btnRow.setLayoutParams(bl);

        if (!info.forceUpdate) {
            TextView btnLater = new TextView(requireContext());
            btnLater.setText("稍后再说");
            btnLater.setTextSize(14f);
            btnLater.setTextColor(accentColor);
            btnLater.setGravity(Gravity.CENTER);
            GradientDrawable laterBg = new GradientDrawable();
            laterBg.setShape(GradientDrawable.RECTANGLE);
            laterBg.setCornerRadius(dp2px(10));
            laterBg.setColor(Color.argb(18, r, g, b));
            laterBg.setStroke(dp2px(1), Color.argb(80, r, g, b));
            btnLater.setBackground(laterBg);
            btnLater.setClickable(true);
            btnLater.setFocusable(true);
            btnLater.setOnClickListener(v -> dialog.dismiss());

            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, dp2px(44), 1);
            ll.rightMargin = dp2px(8);
            btnLater.setLayoutParams(ll);
            btnRow.addView(btnLater);
        }

        TextView btnUpdate = new TextView(requireContext());
        btnUpdate.setText("立即更新");
        btnUpdate.setTextSize(14f);
        btnUpdate.setTextColor(0xFFFFFFFF);
        btnUpdate.setGravity(Gravity.CENTER);
        GradientDrawable upBg = new GradientDrawable();
        upBg.setShape(GradientDrawable.RECTANGLE);
        upBg.setCornerRadius(dp2px(10));
        upBg.setColor(accentColor);
        btnUpdate.setBackground(upBg);
        btnUpdate.setClickable(true);
        btnUpdate.setFocusable(true);
        final String dlUrl = info.downloadUrl;
        btnUpdate.setOnClickListener(v -> {
            dialog.dismiss();
            UpdateInstaller.downloadAndInstall(requireContext(), dlUrl, new UpdateInstaller.InstallCallback() {
                        @Override public void onSuccess() {}
                        @Override public void onError(String msg) {
                            ToastUtil.showLong(requireContext(), "更新失败: " + msg);
                        }
                    });
        });

        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(0, dp2px(44), 1);
        if (info.forceUpdate) {
            ul.leftMargin = 0;
        } else {
            ul.leftMargin = dp2px(8);
        }
        btnUpdate.setLayoutParams(ul);
        btnRow.addView(btnUpdate);

        root.addView(btnRow);
        dialog.setContentView(root);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            window.setAttributes(lp);
        }
        dialog.show();
    }



    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private com.google.android.material.dialog.MaterialAlertDialogBuilder newThemeProgressDialog(String title, String message) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setCancelable(false);
        return builder;
    }

    // ---- FTP 用户管理 ----

    private org.json.JSONArray getFtpUsers() {
        try {
            return new org.json.JSONArray(prefs.getString("ftp_users", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private void saveFtpUsers(org.json.JSONArray arr) {
        prefs.edit().putString("ftp_users", arr.toString()).apply();
        refreshFtpUserCount();
    }

    private void showFtpUserManagerSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        android.widget.LinearLayout headerRow = new android.widget.LinearLayout(requireContext());
        headerRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp2px(16));

        android.widget.TextView title = new android.widget.TextView(requireContext());
        title.setText("FTP 用户管理");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.LinearLayout.LayoutParams titleLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        title.setLayoutParams(titleLp);
        headerRow.addView(title);

        android.widget.TextView addBtn = new android.widget.TextView(requireContext());
        addBtn.setText("+ 添加");
        addBtn.setTextSize(13f);
        addBtn.setTextColor(getThemeAccentColor());
        addBtn.setClickable(true);
        addBtn.setFocusable(true);
        android.graphics.drawable.GradientDrawable addBg = new android.graphics.drawable.GradientDrawable();
        addBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        addBg.setCornerRadius(dp2px(8));
        addBg.setColor(lightenColor(getThemeAccentColor(), 0.92f));
        addBg.setStroke(dp2px(1), lightenColor(getThemeAccentColor(), 0.7f));
        addBtn.setBackground(addBg);
        addBtn.setPadding(dp2px(14), dp2px(6), dp2px(14), dp2px(6));
        addBtn.setOnClickListener(v -> {
            sheet.dismiss();
            showFtpUserEditSheet(null);
        });
        headerRow.addView(addBtn);

        root.addView(headerRow);

        org.json.JSONArray users = getFtpUsers();
        if (users.length() == 0) {
            android.widget.TextView empty = new android.widget.TextView(requireContext());
            empty.setText("暂未添加用户");
            empty.setTextSize(14f);
            empty.setTextColor(0xFF9AA0A6);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp2px(24), 0, dp2px(24));
            root.addView(empty);
        } else {
            for (int i = 0; i < users.length(); i++) {
                try {
                    org.json.JSONObject u = users.getJSONObject(i);
                    String username = u.optString("username", "");
                    String homeDir = u.optString("homeDir", "");

                    android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
                    android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                    rowBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    rowBg.setCornerRadius(dp2px(12));
                    rowBg.setColor(0xFFF7F8FA);
                    rowBg.setStroke(dp2px(1), 0xFFE2E6EA);
                    row.setBackground(rowBg);

                    android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp2px(8);
                    row.setLayoutParams(rowLp);

                    android.view.View indicator = new android.view.View(requireContext());
                    android.widget.LinearLayout.LayoutParams indLp = new android.widget.LinearLayout.LayoutParams(
                            dp2px(3), android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
                    indLp.setMarginEnd(dp2px(12));
                    indicator.setLayoutParams(indLp);
                    android.graphics.drawable.GradientDrawable indBg = new android.graphics.drawable.GradientDrawable();
                    indBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    indBg.setCornerRadius(dp2px(2));
                    indBg.setColor(getThemeAccentColor());
                    indicator.setBackground(indBg);
                    row.addView(indicator);

                    android.widget.LinearLayout col = new android.widget.LinearLayout(requireContext());
                    col.setOrientation(android.widget.LinearLayout.VERTICAL);
                    col.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                    android.widget.TextView nameTv = new android.widget.TextView(requireContext());
                    nameTv.setText(username);
                    nameTv.setTextSize(15f);
                    nameTv.setTextColor(0xFF202124);
                    nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
                    col.addView(nameTv);

                    android.widget.TextView homeTv = new android.widget.TextView(requireContext());
                    homeTv.setText(homeDir.isEmpty() ? "默认主目录" : homeDir);
                    homeTv.setTextSize(11f);
                    homeTv.setTextColor(0xFF9AA0A6);
                    homeTv.setMaxLines(1);
                    homeTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    android.widget.LinearLayout.LayoutParams homeLp = new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    homeTv.setLayoutParams(homeLp);
                    col.addView(homeTv);

                    row.addView(col);

                    android.widget.TextView editBtn = new android.widget.TextView(requireContext());
                    editBtn.setText("编辑");
                    editBtn.setTextSize(12f);
                    editBtn.setTextColor(0xFF1A73E8);
                    editBtn.setClickable(true);
                    editBtn.setFocusable(true);
                    android.graphics.drawable.GradientDrawable ebg = new android.graphics.drawable.GradientDrawable();
                    ebg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    ebg.setCornerRadius(dp2px(8));
                    ebg.setColor(0xFFE8F0FE);
                    editBtn.setBackground(ebg);
                    editBtn.setPadding(dp2px(10), dp2px(4), dp2px(10), dp2px(4));
                    android.widget.LinearLayout.LayoutParams eLp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    eLp.setMarginEnd(dp2px(8));
                    editBtn.setLayoutParams(eLp);
                    final org.json.JSONObject fu = u;
                    editBtn.setOnClickListener(v -> {
                        sheet.dismiss();
                        showFtpUserEditSheet(fu);
                    });
                    row.addView(editBtn);

                    android.widget.TextView delBtn = new android.widget.TextView(requireContext());
                    delBtn.setText("删除");
                    delBtn.setTextSize(12f);
                    delBtn.setTextColor(0xFFEA4335);
                    delBtn.setClickable(true);
                    delBtn.setFocusable(true);
                    android.graphics.drawable.GradientDrawable dbg = new android.graphics.drawable.GradientDrawable();
                    dbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    dbg.setCornerRadius(dp2px(8));
                    dbg.setColor(0xFFFDECEC);
                    delBtn.setBackground(dbg);
                    delBtn.setPadding(dp2px(10), dp2px(4), dp2px(10), dp2px(4));
                    final String finalUsername = username;
                    delBtn.setOnClickListener(v -> {
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                .setTitle("删除用户")
                                .setMessage("确定要删除用户 \"" + finalUsername + "\" 吗？")
                                .setPositiveButton("删除", (d, w) -> {
                                    try {
                                        org.json.JSONArray arr = getFtpUsers();
                                        for (int k = 0; k < arr.length(); k++) {
                                            if (arr.getJSONObject(k).optString("username").equals(finalUsername)) {
                                                arr.remove(k);
                                                break;
                                            }
                                        }
                                        saveFtpUsers(arr);
                                        sheet.dismiss();
                                        showFtpUserManagerSheet();
                                    } catch (Exception ex) {
                                        ToastUtil.showShort(getContext(), "删除失败: " + ex.getMessage());
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    });
                    row.addView(delBtn);

                    root.addView(row);
                } catch (Exception ex) {
                    // skip
                }
            }
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(requireContext());
        sc.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, -1));
        sc.setFillViewport(true);
        sc.addView(root);
        sheet.setContentView(sc);
        sheet.setOnShowListener(d -> {
            android.widget.FrameLayout bottomSheet = sheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                        .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
            sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        sheet.show();
    }

    private void showFtpUserEditSheet(@Nullable org.json.JSONObject existing) {
        boolean isEdit = existing != null;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        android.widget.TextView title = new android.widget.TextView(requireContext());
        title.setText(isEdit ? "编辑 FTP 用户" : "添加 FTP 用户");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp2px(16));
        root.addView(title);

        // 用户名
        android.widget.TextView nameLabel = new android.widget.TextView(requireContext());
        nameLabel.setText("用户名");
        nameLabel.setTextSize(12f);
        nameLabel.setTextColor(0xFF5F6368);
        nameLabel.setPadding(0, dp2px(4), 0, dp2px(4));
        root.addView(nameLabel);
        android.widget.EditText inputName = new android.widget.EditText(requireContext());
        inputName.setHint("请输入用户名");
        inputName.setTextSize(14f);
        inputName.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        inputName.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputName.setSingleLine(true);
        if (isEdit) inputName.setText(existing.optString("username"));
        root.addView(inputName);

        // 密码
        android.widget.TextView passLabel = new android.widget.TextView(requireContext());
        passLabel.setText("密码");
        passLabel.setTextSize(12f);
        passLabel.setTextColor(0xFF5F6368);
        passLabel.setPadding(0, dp2px(12), 0, dp2px(4));
        root.addView(passLabel);
        android.widget.EditText inputPass = new android.widget.EditText(requireContext());
        inputPass.setHint(isEdit ? "留空则不修改" : "请输入密码");
        inputPass.setTextSize(14f);
        inputPass.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        inputPass.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputPass.setSingleLine(true);
        inputPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (isEdit) inputPass.setText(existing.optString("password"));
        root.addView(inputPass);

        // 主目录
        android.widget.TextView homeLabel = new android.widget.TextView(requireContext());
        homeLabel.setText("主目录（用户可访问的根路径）");
        homeLabel.setTextSize(12f);
        homeLabel.setTextColor(0xFF5F6368);
        homeLabel.setPadding(0, dp2px(12), 0, dp2px(4));
        root.addView(homeLabel);
        android.widget.EditText inputHome = new android.widget.EditText(requireContext());
        String defaultHome = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        inputHome.setHint("留空使用: " + defaultHome);
        inputHome.setTextSize(14f);
        inputHome.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        inputHome.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputHome.setSingleLine(true);
        if (isEdit) inputHome.setText(existing.optString("homeDir"));
        root.addView(inputHome);

        // 提示
        android.widget.TextView hint = new android.widget.TextView(requireContext());
        hint.setText("用户可对主目录及子目录进行读写操作");
        hint.setTextSize(11f);
        hint.setTextColor(0xFF9AA0A6);
        hint.setPadding(0, dp2px(8), 0, 0);
        root.addView(hint);

        // 保存按钮
        android.widget.TextView saveBtn = new android.widget.TextView(requireContext());
        saveBtn.setText(isEdit ? "保存修改" : "添加用户");
        saveBtn.setTextSize(15f);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setGravity(android.view.Gravity.CENTER);
        saveBtn.setClickable(true);
        saveBtn.setFocusable(true);
        int btnPadV = dp2px(12);
        saveBtn.setPadding(0, btnPadV, 0, btnPadV);
        android.graphics.drawable.GradientDrawable sBg = new android.graphics.drawable.GradientDrawable();
        sBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        sBg.setCornerRadius(dp2px(10));
        sBg.setColor(getThemeAccentColor());
        saveBtn.setBackground(sBg);
        android.widget.LinearLayout.LayoutParams sLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp2px(20);
        saveBtn.setLayoutParams(sLp);
        root.addView(saveBtn);

        saveBtn.setOnClickListener(v -> {
            String name = inputName.getText().toString().trim();
            String pass = inputPass.getText().toString();
            String home = inputHome.getText().toString().trim();
            if (name.isEmpty()) {
                ToastUtil.showShort(getContext(), "用户名不能为空");
                return;
            }
            if (!isEdit && pass.isEmpty()) {
                ToastUtil.showShort(getContext(), "密码不能为空");
                return;
            }
            if (home.isEmpty()) home = defaultHome;
            final String finalHome = home;
            try {
                java.io.File hd = new java.io.File(home);
                if (!hd.exists()) hd.mkdirs();
            } catch (Exception ignored) {}

            androidx.appcompat.app.AlertDialog progress = newThemeProgressDialog(
                    isEdit ? "修改中" : "添加中",
                    isEdit ? "正在修改用户信息，请稍候..." : "正在添加用户，请稍候...").show();
            new Thread(() -> {
                boolean success = false;
                String errorMsg = null;
                try {
                    org.json.JSONArray arr = getFtpUsers();
                    if (!isEdit) {
                        for (int k = 0; k < arr.length(); k++) {
                            if (arr.getJSONObject(k).optString("username").equals(name)) {
                                errorMsg = "用户名已存在";
                                return;
                            }
                        }
                        org.json.JSONObject u = new org.json.JSONObject();
                        u.put("username", name);
                        u.put("password", pass);
                        u.put("homeDir", finalHome);
                        arr.put(u);
                    } else {
                        String oldName = existing.optString("username");
                        for (int k = 0; k < arr.length(); k++) {
                            org.json.JSONObject o = arr.getJSONObject(k);
                            if (o.optString("username").equals(oldName)) {
                                o.put("username", name);
                                if (!pass.isEmpty()) o.put("password", pass);
                                o.put("homeDir", finalHome);
                                break;
                            }
                        }
                    }
                    saveFtpUsers(arr);
                    success = true;
                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                }
                final boolean finalSuccess = success;
                final String finalErrorMsg = errorMsg;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try { if (progress != null) progress.dismiss(); } catch (Exception ignored) {}
                        if (finalSuccess) {
                            try { sheet.dismiss(); } catch (Exception ignored) {}
                            ToastUtil.showShort(getContext(), isEdit ? "已保存" : "已添加");
                            showFtpUserManagerSheet();
                        } else if (finalErrorMsg != null) {
                            ToastUtil.showLong(getContext(), "失败: " + finalErrorMsg);
                        }
                    });
                }
            }).start();
        });

        android.widget.ScrollView sc = new android.widget.ScrollView(requireContext());
        sc.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, -1));
        sc.setFillViewport(true);
        sc.addView(root);
        sheet.setContentView(sc);
        sheet.setOnShowListener(d -> {
            android.widget.FrameLayout bottomSheet = sheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                        .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
            sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        sheet.show();
    }


    // ----- 主题色应用到所有 SwitchMaterial -----

    private void applyThemeToSwitches() {
        int primaryColor = getPrimaryColor();
        int r = Color.red(primaryColor);
        int g = Color.green(primaryColor);
        int b = Color.blue(primaryColor);
        int trackColor = Color.argb(77, r, g, b);
        int[][] states = new int[][]{
            {android.R.attr.state_checked},
            {-android.R.attr.state_checked}
        };
        int[] thumbColors = new int[]{primaryColor, android.graphics.Color.parseColor("#FFFAFAFA")};
        int[] trackColors = new int[]{trackColor, android.graphics.Color.parseColor("#33000000")};
        android.content.res.ColorStateList thumbCsl = new android.content.res.ColorStateList(states, thumbColors);
        android.content.res.ColorStateList trackCsl = new android.content.res.ColorStateList(states, trackColors);
        if (switchMysqlWan != null) {
            switchMysqlWan.setThumbTintList(thumbCsl);
            switchMysqlWan.setTrackTintList(trackCsl);
        }
        if (switchFtpWan != null) {
            switchFtpWan.setThumbTintList(thumbCsl);
            switchFtpWan.setTrackTintList(trackCsl);
        }
        if (switchWsWan != null) {
            switchWsWan.setThumbTintList(thumbCsl);
            switchWsWan.setTrackTintList(trackCsl);
        }
    }

    private int getPrimaryColor() {
        String themeKey = prefs.getString("theme_key", "slate");
        switch (themeKey) {
            case "green":  return ContextCompat.getColor(requireContext(), R.color.theme_green_primary);
            case "purple": return ContextCompat.getColor(requireContext(), R.color.theme_purple_primary);
            case "orange": return ContextCompat.getColor(requireContext(), R.color.theme_orange_primary);
            case "blue":   return ContextCompat.getColor(requireContext(), R.color.blue_primary);
            case "red":    return ContextCompat.getColor(requireContext(), R.color.theme_red_primary);
            case "cyan":   return ContextCompat.getColor(requireContext(), R.color.theme_cyan_primary);
            case "pink":   return ContextCompat.getColor(requireContext(), R.color.theme_pink_primary);
            case "indigo": return ContextCompat.getColor(requireContext(), R.color.theme_indigo_primary);
            case "brown":  return ContextCompat.getColor(requireContext(), R.color.theme_brown_primary);
            default:       return ContextCompat.getColor(requireContext(), R.color.theme_slate_primary);
        }
    }


    /* ----- 内网穿透（二级窗口） ----- */

    private void refreshTunnelStatus() {
        if (getView() == null) return;
        TextView tv = getView().findViewById(R.id.tv_tunnel_status);
        if (tv == null) return;
        try {
            // 优先显示 cpolar 状态，没有则显示 ME Frp 状态
            String cpolarUrl = "";
            boolean cpolarRunning = false;
            String cpolarError = "";
            if (CpolarManager.isInitialized()) {
                CpolarManager mgr = CpolarManager.get();
                cpolarRunning = mgr.isRunning();
                cpolarUrl = mgr.getPublicUrl();
                if (cpolarUrl.isEmpty()) cpolarUrl = CpolarConfig.getPublicUrl(requireContext());
                cpolarError = mgr.getLastError();
                if (cpolarError.isEmpty()) cpolarError = CpolarConfig.getLastError(requireContext());
            }
            boolean mefrpRunning = MefrpService.isRunning();
            String mefrpUrl = MefrpService.getPublicUrl();
            if (mefrpUrl.isEmpty()) mefrpUrl = com.zm920.androidserver.mefrp.MefrpConfig.getPublicUrl(requireContext());

            if (cpolarRunning || mefrpRunning) {
                String url = !cpolarUrl.isEmpty() ? cpolarUrl : mefrpUrl;
                tv.setText("运行中");
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_green_primary));
            } else if (!cpolarError.isEmpty()) {
                tv.setText("cpolar 失败");
                tv.setTextColor(0xFFD93025);
            } else {
                tv.setText("未启动");
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            }
        } catch (Exception ignored) {}
    }

    /** 二级窗口：选择 cpolar 或 ME Frp */
    private void showTunnelBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        TextView title = new TextView(requireContext());
        title.setText("内网穿透");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp2px(16);
        title.setLayoutParams(titleLp);
        root.addView(title);

        // cpolar 入口
        LinearLayout cpolarRow = makeTunnelRow("cpolar", "cpolar.com 提供(可能弃用)", "配置");
        // 注册按钮：打开 cpolar 官网
        TextView cpolarRegister = (TextView) cpolarRow.findViewWithTag("register");
        if (cpolarRegister != null) {
            cpolarRegister.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.cpolar.com/"));
                    startActivity(i);
                } catch (Exception ex) {
                    ToastUtil.showShort(requireContext(), "无法打开浏览器");
                }
            });
        }
        cpolarRow.setOnClickListener(v -> {
            sheet.dismiss();
            showCpolarBottomSheet();
        });
        root.addView(cpolarRow);

        // 分割线
        View div = new View(requireContext());
        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(0x1A000000);
        LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) div.getLayoutParams();
        divLp.topMargin = dp2px(8);
        divLp.bottomMargin = dp2px(8);
        root.addView(div);

        // ME Frp 入口
        LinearLayout mefrpRow = makeTunnelRow("ME Frp", "mefrp.com 提供(推荐)", "配置");
        // 注册按钮：打开 mefrp 官网
        TextView mefrpRegister = (TextView) mefrpRow.findViewWithTag("register");
        if (mefrpRegister != null) {
            mefrpRegister.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.mefrp.com/"));
                    startActivity(i);
                } catch (Exception ex) {
                    ToastUtil.showShort(requireContext(), "无法打开浏览器");
                }
            });
        }
        mefrpRow.setOnClickListener(v -> {
            // 检查是否已部署，未部署则弹窗自动部署
            if (com.zm920.androidserver.mefrp.MefrpManager.get(requireContext()).isBinReady()) {
                sheet.dismiss();
                showMefrpBottomSheet();
            } else {
                showMefrpAutoDeployDialog(sheet);
            }
        });
        root.addView(mefrpRow);

        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.show();
    }

    private LinearLayout makeTunnelRow(String name, String desc, String action) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(12), dp2px(14), dp2px(12), dp2px(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp2px(12));
        bg.setColor(0xFFF7F8FA);
        bg.setStroke(dp2px(1), 0xFFE2E6EA);
        row.setBackground(bg);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView nameTv = new TextView(requireContext());
        nameTv.setText(name);
        nameTv.setTextSize(15f);
        nameTv.setTextColor(0xFF202124);
        nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(nameTv);

        TextView descTv = new TextView(requireContext());
        descTv.setText(desc);
        descTv.setTextSize(11f);
        descTv.setTextColor(0xFF9AA0A6);
        col.addView(descTv);

        row.addView(col);

        // 注册按钮（左侧）
        TextView registerTv = new TextView(requireContext());
        registerTv.setText("注册");
        registerTv.setTextSize(13f);
        registerTv.setTextColor(getThemeAccentColor());
        registerTv.setPadding(dp2px(8), dp2px(6), dp2px(8), dp2px(6));
        registerTv.setClickable(true);
        registerTv.setFocusable(true);
        registerTv.setTag("register");
        row.addView(registerTv);

        TextView actionTv = new TextView(requireContext());
        actionTv.setText(action);
        actionTv.setTextSize(13f);
        actionTv.setTextColor(getThemeAccentColor());
        actionTv.setPadding(dp2px(8), 0, 0, 0);
        row.addView(actionTv);

        return row;
    }

    /* ----- cpolar 内网穿透 ----- */

    private void refreshCpolarStatus() {
        if (getView() == null) return;
        TextView tv = getView().findViewById(R.id.tv_tunnel_status);
        if (tv == null) return;
        try {
            String url = "";
            boolean isRunning = false;
            String error = "";
            String status = "stopped";
            if (CpolarManager.isInitialized()) {
                CpolarManager mgr = CpolarManager.get();
                isRunning = mgr.isRunning();
                url = mgr.getPublicUrl();
                if (url.isEmpty()) url = CpolarConfig.getPublicUrl(requireContext());
                error = mgr.getLastError();
                if (error.isEmpty()) error = CpolarConfig.getLastError(requireContext());
            }
            if (isRunning) {
                if (!url.isEmpty()) {
                    tv.setText("运行中");
                    tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_green_primary));
                    status = "running";
                } else {
                    tv.setText("启动中...");
                    tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_orange_primary));
                    status = "starting";
                }
            } else if (error != null && !error.isEmpty()) {
                // 在紧凑标签上只显示简短错误，完整原因看弹窗
                String brief = error.length() > 30 ? error.substring(0, 30) + "..." : error;
                tv.setText("失败: " + brief);
                tv.setTextColor(0xFFD93025);
                status = "error";
            } else {
                tv.setText("未启动");
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            }
            // 通知当前打开的 BottomSheet 刷新
            refreshOpenCpolarSheet(status, url);
        } catch (Exception ignored) {}
    }

    /** 当前打开的 cpolar BottomSheet（用于实时刷新） */
    private android.view.View currentCpolarSheetRoot;
    private android.widget.TextView currentCpolarSheetStatus;
    private android.widget.TextView currentCpolarSheetUrl;
    private Runnable currentCpolarSheetRefresh;

    private void setCpolarSheetRefs(android.view.View root, android.widget.TextView statusTv, android.widget.TextView urlTv, Runnable refresh) {
        this.currentCpolarSheetRoot = root;
        this.currentCpolarSheetStatus = statusTv;
        this.currentCpolarSheetUrl = urlTv;
        this.currentCpolarSheetRefresh = refresh;
    }

    private void clearCpolarSheetRefs() {
        currentCpolarSheetRoot = null;
        currentCpolarSheetStatus = null;
        currentCpolarSheetUrl = null;
        currentCpolarSheetRefresh = null;
    }

    private void refreshOpenCpolarSheet(String status, String url) {
        try {
            if (currentCpolarSheetRefresh != null) currentCpolarSheetRefresh.run();
        } catch (Exception ignored) {}
    }

    /** 当前打开的 mefrp BottomSheet（用于实时刷新） */
    private android.widget.TextView currentMefrpSheetStatus;
    private android.widget.TextView currentMefrpSheetUrl;
    private Runnable currentMefrpSheetRefresh;

    private void setMefrpSheetRefs(android.widget.TextView statusTv, android.widget.TextView urlTv, Runnable refresh) {
        this.currentMefrpSheetStatus = statusTv;
        this.currentMefrpSheetUrl = urlTv;
        this.currentMefrpSheetRefresh = refresh;
    }

    private void clearMefrpSheetRefs() {
        currentMefrpSheetStatus = null;
        currentMefrpSheetUrl = null;
        currentMefrpSheetRefresh = null;
    }

    private void refreshOpenMefrpSheet(String status, String url) {
        try {
            if (currentMefrpSheetRefresh != null) currentMefrpSheetRefresh.run();
        } catch (Exception ignored) {}
    }

    private void showCpolarBottomSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        TextView title = new TextView(requireContext());
        title.setText("内网穿透 (cpolar)");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp2px(16);
        title.setLayoutParams(titleLp);
        root.addView(title);

        // 状态 + 公网 URL
        TextView tvStatus = new TextView(requireContext());
        tvStatus.setTextSize(13f);
        tvStatus.setPadding(0, 0, 0, dp2px(6));
        root.addView(tvStatus);
        TextView tvUrl = new TextView(requireContext());
        tvUrl.setTextSize(14f);
        tvUrl.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvUrl.setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8));
        tvUrl.setBackgroundColor(0xFFF1F3F4);
        tvUrl.setTextIsSelectable(true);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.bottomMargin = dp2px(8);
        tvUrl.setLayoutParams(urlLp);
        root.addView(tvUrl);



        Runnable refreshAll = () -> {
            try {
                String url = "";
                boolean isRunning = false;
                String error = "";
                if (CpolarManager.isInitialized()) {
                    CpolarManager mgr = CpolarManager.get();
                    isRunning = mgr.isRunning();
                    url = mgr.getPublicUrl();
                    if (url.isEmpty()) url = CpolarConfig.getPublicUrl(requireContext());
                    error = mgr.getLastError();
                    if (error.isEmpty()) error = CpolarConfig.getLastError(requireContext());
                }
                if (isRunning) {
                    tvStatus.setText("状态: 运行中");
                    tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_green_primary));
                    if (url.isEmpty()) url = "(正在分配隧道...)";
                    tvUrl.setText(url);
                    tvUrl.setTextColor(0xFF202124);
                } else if (error != null && !error.isEmpty()) {
                    tvStatus.setText("启动失败");
                    tvStatus.setTextColor(0xFFD93025);
                    tvUrl.setText(error);
                    tvUrl.setTextColor(0xFFD93025);
                } else {
                    tvStatus.setText("状态: 未运行");
                    tvStatus.setTextColor(0xFF9AA0A6);
                    tvUrl.setText("暂无公网 URL");
                    tvUrl.setTextColor(0xFF202124);
                }
            } catch (Exception e) {
                tvStatus.setText("状态: 错误 - " + e.getMessage());
                tvStatus.setTextColor(0xFFD93025);
                tvUrl.setText("");
            }
        };
        refreshAll.run();
        // 注册到全局让 broadcast 触发时刷新
        setCpolarSheetRefs(root, tvStatus, tvUrl, refreshAll);

        // 启动 / 停止 按钮
        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnStart = bigButton("启动");
        TextView btnStop = bigButton("停止");
        btnStart.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), CpolarService.class);
            i.setAction(CpolarService.ACTION_START);
            requireContext().startForegroundService(i);
            refreshAll.run();
            ToastUtil.showShort(requireContext(), "cpolar 启动指令已发送");
        });
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), CpolarService.class);
            i.setAction(CpolarService.ACTION_STOP);
            requireContext().startService(i);
            refreshAll.run();
            ToastUtil.showShort(requireContext(), "已停止");
        });
        TextView btnClearLog = bigButton("清日志");
        btnClearLog.setOnClickListener(v -> {
            try {
                java.io.File f = CpolarManager.get(requireContext()).getLogFile();
                if (f != null && f.exists()) {
                    f.delete();
                }
                ToastUtil.showShort(requireContext(), "日志已清空");
            } catch (Exception ex) {
                ToastUtil.showShort(requireContext(), "清空失败: " + ex.getMessage());
            }
        });
        // 三按钮行
        btnRow.addView(btnStart, new LinearLayout.LayoutParams(0, dp2px(44), 1));
        btnRow.addView(spacer(dp2px(6)));
        btnRow.addView(btnStop, new LinearLayout.LayoutParams(0, dp2px(44), 1));
        btnRow.addView(spacer(dp2px(6)));
        btnRow.addView(btnClearLog, new LinearLayout.LayoutParams(0, dp2px(44), 1));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.bottomMargin = dp2px(16);
        btnRow.setLayoutParams(btnLp);
        root.addView(btnRow);



        // 配置区
        TextView cfgTitle = new TextView(requireContext());
        cfgTitle.setText("配置");
        cfgTitle.setTextSize(14f);
        cfgTitle.setTextColor(0xFF202124);
        cfgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams cfgTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cfgTitleLp.topMargin = dp2px(12);
        cfgTitleLp.bottomMargin = dp2px(8);
        cfgTitle.setLayoutParams(cfgTitleLp);
        root.addView(cfgTitle);

        // Token
        addLabel(root, "Auth Token (cpolar.com 后台获取)");
        EditText etToken = makeEdit();
        etToken.setText(CpolarConfig.getToken(requireContext()));
        etToken.setTypeface(android.graphics.Typeface.MONOSPACE);
        etToken.setTextSize(12f);
        root.addView(etToken);

        // 目标端口
        addLabel(root, "目标本地端口 (将公网映射到这个端口)");
        EditText etPort = makeEdit();
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(CpolarConfig.getTargetPort(requireContext())));
        root.addView(etPort);
        TextView btnUseDemo = smallAction("使用 18080 (内置演示服务)");
        btnUseDemo.setOnClickListener(v -> {
            etPort.setText("18080");
            CpolarConfig.setTargetPort(requireContext(), 18080);
            ToastUtil.showShort(requireContext(), "已设为 18080 (内置演示)");
        });
        root.addView(btnUseDemo);

        // 区域
        addLabel(root, "区域 (cn=国内 / cn_top=国内高优 / cn_vip=国内VIP / us=海外)");
        EditText etRegion = makeEdit();
        etRegion.setText(CpolarConfig.getRegion(requireContext()));
        root.addView(etRegion);

        // 协议
        addLabel(root, "协议 (http / tcp)");
        EditText etProto = makeEdit();
        etProto.setText(CpolarConfig.getProto(requireContext()));
        root.addView(etProto);

        // 日志级别
        addLabel(root, "日志级别 (DEBUG/INFO/WARNING/ERROR)");
        EditText etLog = makeEdit();
        etLog.setText(CpolarConfig.getLogLevel(requireContext()));
        root.addView(etLog);

        TextView btnSave = smallAction("保存配置");
        btnSave.setOnClickListener(v -> {
            try {
                CpolarConfig.setToken(requireContext(), etToken.getText().toString().trim());
                int p = Integer.parseInt(etPort.getText().toString().trim());
                if (p <= 0 || p > 65535) throw new Exception("端口无效");
                CpolarConfig.setTargetPort(requireContext(), p);
                CpolarConfig.setRegion(requireContext(), etRegion.getText().toString().trim());
                CpolarConfig.setProto(requireContext(), etProto.getText().toString().trim());
                CpolarConfig.setLogLevel(requireContext(), etLog.getText().toString().trim());
                ToastUtil.showShort(requireContext(), "已保存");
            } catch (Exception e) {
                ToastUtil.showShort(requireContext(), "保存失败: " + e.getMessage());
            }
        });
        root.addView(btnSave);
        // 复制 URL
        TextView btnCopy = smallAction("复制公网 URL");
        btnCopy.setOnClickListener(v -> {
            String url = tvUrl.getText().toString();
            if (url.isEmpty() || url.equals("暂无公网 URL")) {
                ToastUtil.showShort(requireContext(), "暂无可复制的 URL");
            } else {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("cpolar url", url));
                ToastUtil.showShort(requireContext(), "已复制");
            }
        });
        root.addView(btnCopy);


        TextView btnCheck = smallAction("环境自检 (诊断用)");
        btnCheck.setOnClickListener(v -> showCpolarCheckSheet());
        root.addView(btnCheck);

        TextView btnLog = smallAction("查看运行日志");
        btnLog.setOnClickListener(v -> showCpolarLogSheet());
        root.addView(btnLog);

        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.setOnDismissListener(d -> clearCpolarSheetRefs());
        sheet.show();
    }
    private com.zm920.androidserver.server.WebServer buildWebServer() {
        try {
            com.zm920.androidserver.service.ProcessManager pm = com.zm920.androidserver.service.ProcessManager.getInstance(requireContext().getFilesDir());
            com.zm920.androidserver.server.BinaryDeployer deployer = new com.zm920.androidserver.server.BinaryDeployer(requireContext(), prefs);
            com.zm920.androidserver.server.StartupLogger sl = new com.zm920.androidserver.server.StartupLogger(requireContext());
            String dataDir = requireContext().getFilesDir().getAbsolutePath() + "/server";
            com.zm920.androidserver.config.ConfigGenerator cg = new com.zm920.androidserver.config.ConfigGenerator(new java.io.File(dataDir + "/config"), prefs);
            return new com.zm920.androidserver.server.WebServer(requireContext(), pm, cg, prefs, deployer, sl);
        } catch (Exception e) {
            return null;
        }
    }

    private void showCpolarCheckSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));
        TextView title = new TextView(requireContext());
        title.setText("cpolar 环境自检");
        title.setTextSize(16f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(8);
        title.setLayoutParams(lp);
        root.addView(title);
        TextView tv = new TextView(requireContext());
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        try {
            tv.setText(CpolarManager.get(requireContext()).selfCheck());
        } catch (Exception e) {
            tv.setText("自检失败: " + e.getMessage());
        }
        tv.setTextIsSelectable(true);
        root.addView(tv);
        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.show();
    }

    private void showCpolarLogSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));
        TextView title = new TextView(requireContext());
        title.setText("cpolar 日志");
        title.setTextSize(16f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(8);
        title.setLayoutParams(lp);
        root.addView(title);
        TextView tv = new TextView(requireContext());
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        try {
            tv.setText(CpolarManager.get(requireContext()).readTailLog(20 * 1024));
        } catch (Exception e) {
            tv.setText("读取失败: " + e.getMessage());
        }
        tv.setTextIsSelectable(true);
        root.addView(tv);
        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.show();
    }

    /* ----- ME Frp 内网穿透 ----- */

    private void showMefrpBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));

        // 标题
        TextView title = new TextView(requireContext());
        title.setText("内网穿透 (ME Frp)");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp2px(16);
        title.setLayoutParams(titleLp);
        root.addView(title);

        // ========== 状态卡片 ==========
        LinearLayout statusCard = new LinearLayout(requireContext());
        statusCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setShape(GradientDrawable.RECTANGLE);
        statusBg.setCornerRadius(dp2px(12));
        statusBg.setColor(0xFFF7F8FA);
        statusBg.setStroke(dp2px(1), 0xFFE2E6EA);
        statusCard.setBackground(statusBg);
        int cardPad = dp2px(16);
        statusCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp2px(20);
        statusCard.setLayoutParams(cardLp);

        // 状态行：状态文字
        TextView tvStatus = new TextView(requireContext());
        tvStatus.setTextSize(14f);
        tvStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tvStatusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvStatusLp.bottomMargin = dp2px(10);
        tvStatus.setLayoutParams(tvStatusLp);
        statusCard.addView(tvStatus);

        // URL 行：URL + 复制图标
        LinearLayout urlRow = new LinearLayout(requireContext());
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        GradientDrawable urlBg = new GradientDrawable();
        urlBg.setShape(GradientDrawable.RECTANGLE);
        urlBg.setCornerRadius(dp2px(8));
        urlBg.setColor(0xFFFFFFFF);
        urlBg.setStroke(dp2px(1), 0xFFE2E6EA);
        urlRow.setBackground(urlBg);
        urlRow.setPadding(dp2px(12), dp2px(10), dp2px(6), dp2px(10));
        LinearLayout.LayoutParams urlRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlRowLp.bottomMargin = dp2px(12);
        urlRow.setLayoutParams(urlRowLp);

        TextView tvUrl = new TextView(requireContext());
        tvUrl.setTextSize(13f);
        tvUrl.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvUrl.setTextIsSelectable(true);
        LinearLayout.LayoutParams tvUrlLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvUrl.setLayoutParams(tvUrlLp);
        urlRow.addView(tvUrl);

        // 复制图标按钮
        TextView btnCopy = new TextView(requireContext());
        btnCopy.setText("📋");
        btnCopy.setTextSize(18f);
        btnCopy.setPadding(dp2px(8), dp2px(4), dp2px(8), dp2px(4));
        btnCopy.setClickable(true);
        btnCopy.setFocusable(true);
        urlRow.addView(btnCopy);

        statusCard.addView(urlRow);

        // 启动/停止 按钮行
        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnStart = bigButton("启动");
        TextView btnStop = bigButton("停止");
        btnRow.addView(btnStart, new LinearLayout.LayoutParams(0, dp2px(44), 1));
        btnRow.addView(spacer(dp2px(8)));
        btnRow.addView(btnStop, new LinearLayout.LayoutParams(0, dp2px(44), 1));
        statusCard.addView(btnRow);

        root.addView(statusCard);

        // ========== 配置区 ==========
        TextView cfgTitle = new TextView(requireContext());
        cfgTitle.setText("配置");
        cfgTitle.setTextSize(13f);
        cfgTitle.setTextColor(0xFF5F6368);
        cfgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams cfgTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cfgTitleLp.bottomMargin = dp2px(12);
        cfgTitle.setLayoutParams(cfgTitleLp);
        root.addView(cfgTitle);

        // Key
        addLabel(root, "Key *");
        EditText etKey = makeEdit();
        etKey.setText(com.zm920.androidserver.mefrp.MefrpConfig.getKey(requireContext()));
        etKey.setTypeface(android.graphics.Typeface.MONOSPACE);
        etKey.setTextSize(13f);
        LinearLayout.LayoutParams etKeyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etKeyLp.bottomMargin = dp2px(4);
        etKey.setLayoutParams(etKeyLp);
        root.addView(etKey);
        TextView keyHint = new TextView(requireContext());
        keyHint.setText("从 mefrp.com 后台获取");
        keyHint.setTextSize(11f);
        keyHint.setTextColor(0xFF9AA0A6);
        LinearLayout.LayoutParams keyHintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keyHintLp.bottomMargin = dp2px(14);
        keyHint.setLayoutParams(keyHintLp);
        root.addView(keyHint);

        // 隧道号
        addLabel(root, "隧道号 *");
        EditText etTunnelPort = makeEdit();
        etTunnelPort.setText(com.zm920.androidserver.mefrp.MefrpConfig.getTunnelPort(requireContext()));
        LinearLayout.LayoutParams etTpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etTpLp.bottomMargin = dp2px(4);
        etTunnelPort.setLayoutParams(etTpLp);
        root.addView(etTunnelPort);
        TextView tpHint = new TextView(requireContext());
        tpHint.setText("从 mefrp.com 后台获取");
        tpHint.setTextSize(11f);
        tpHint.setTextColor(0xFF9AA0A6);
        LinearLayout.LayoutParams tpHintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tpHintLp.bottomMargin = dp2px(14);
        tpHint.setLayoutParams(tpHintLp);
        root.addView(tpHint);

        // 本地端口
        addLabel(root, "本地端口");
        EditText etLocalPort = makeEdit();
        etLocalPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etLocalPort.setText(String.valueOf(com.zm920.androidserver.mefrp.MefrpConfig.getLocalPort(requireContext())));
        LinearLayout.LayoutParams etLpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLpLp.bottomMargin = dp2px(4);
        etLocalPort.setLayoutParams(etLpLp);
        root.addView(etLocalPort);
        TextView lpHint = new TextView(requireContext());
        lpHint.setText("公网将映射到这个端口");
        lpHint.setTextSize(11f);
        lpHint.setTextColor(0xFF9AA0A6);
        LinearLayout.LayoutParams lpHintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpHintLp.bottomMargin = dp2px(16);
        lpHint.setLayoutParams(lpHintLp);
        root.addView(lpHint);

        LinearLayout autoStartRow = new LinearLayout(requireContext());
        autoStartRow.setOrientation(LinearLayout.HORIZONTAL);
        autoStartRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        autoStartRow.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        GradientDrawable autoStartBg = new GradientDrawable();
        autoStartBg.setShape(GradientDrawable.RECTANGLE);
        autoStartBg.setCornerRadius(dp2px(10));
        autoStartBg.setColor(0xFFF7F8FA);
        autoStartBg.setStroke(dp2px(1), 0xFFE2E6EA);
        autoStartRow.setBackground(autoStartBg);
        LinearLayout.LayoutParams autoStartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        autoStartLp.bottomMargin = dp2px(16);
        autoStartRow.setLayoutParams(autoStartLp);

        LinearLayout autoStartTextCol = new LinearLayout(requireContext());
        autoStartTextCol.setOrientation(LinearLayout.VERTICAL);
        autoStartTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView autoStartTitle = new TextView(requireContext());
        autoStartTitle.setText("软件启动时自动启动内网穿透");
        autoStartTitle.setTextSize(14f);
        autoStartTitle.setTextColor(0xFF202124);
        autoStartTextCol.addView(autoStartTitle);
        TextView autoStartHint = new TextView(requireContext());
        autoStartHint.setText("启动软件 30 秒后检查 Key 和隧道号，填写完整才会启动");
        autoStartHint.setTextSize(11f);
        autoStartHint.setTextColor(0xFF9AA0A6);
        autoStartTextCol.addView(autoStartHint);
        autoStartRow.addView(autoStartTextCol);

        SwitchMaterial switchAutoStart = new SwitchMaterial(requireContext());
        switchAutoStart.setChecked(com.zm920.androidserver.mefrp.MefrpConfig.isAutoStart(requireContext()));
        autoStartRow.addView(switchAutoStart);
        autoStartRow.setOnClickListener(v -> switchAutoStart.setChecked(!switchAutoStart.isChecked()));
        root.addView(autoStartRow);

        // 保存按钮（居中）
        TextView btnSave = bigButton("保存配置");
        LinearLayout.LayoutParams btnSaveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(44));
        btnSaveLp.bottomMargin = dp2px(20);
        btnSave.setLayoutParams(btnSaveLp);
        root.addView(btnSave);

        // ========== 工具区 ==========
        TextView toolTitle = new TextView(requireContext());
        toolTitle.setText("工具");
        toolTitle.setTextSize(13f);
        toolTitle.setTextColor(0xFF5F6368);
        toolTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams toolTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toolTitleLp.bottomMargin = dp2px(8);
        toolTitle.setLayoutParams(toolTitleLp);
        root.addView(toolTitle);

        // 工具列表容器
        LinearLayout toolList = new LinearLayout(requireContext());
        toolList.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable toolBg = new GradientDrawable();
        toolBg.setShape(GradientDrawable.RECTANGLE);
        toolBg.setCornerRadius(dp2px(12));
        toolBg.setColor(0xFFF7F8FA);
        toolBg.setStroke(dp2px(1), 0xFFE2E6EA);
        toolList.setBackground(toolBg);

        // 查看运行日志
        LinearLayout btnLog = makeToolItem("📄", "查看运行日志");
        toolList.addView(btnLog);

        // 分隔线
        View div1 = new View(requireContext());
        div1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div1.setBackgroundColor(0x1A000000);
        toolList.addView(div1);

        // 环境自检
        LinearLayout btnCheck = makeToolItem("🔍", "环境自检");
        toolList.addView(btnCheck);

        root.addView(toolList);

        // ========== refreshAll 逻辑（保持不变）==========
        Runnable refreshAll = () -> {
            try {
                boolean isRunning = MefrpService.isRunning();
                String url = MefrpService.getPublicUrl();
                if (url.isEmpty()) url = com.zm920.androidserver.mefrp.MefrpConfig.getPublicUrl(requireContext());
                String error = MefrpService.getLastError();
                if (error.isEmpty()) error = com.zm920.androidserver.mefrp.MefrpConfig.getLastError(requireContext());
                // isRunning=true 时优先显示运行/启动中状态，不被中间日志的 error 干扰
                if (isRunning && !url.isEmpty()) {
                    // 成功：显示域名
                    tvStatus.setText("● 运行中");
                    tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_green_primary));
                    tvUrl.setText(url);
                    tvUrl.setTextColor(0xFF202124);
                } else if (isRunning) {
                    // 运行中但还没拿到 URL
                    tvStatus.setText("● 运行中");
                    tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_green_primary));
                    tvUrl.setText("(正在分配隧道...)");
                    tvUrl.setTextColor(0xFF9AA0A6);
                } else if (error != null && !error.isEmpty()) {
                    // 失败：显示"失败请查看日志"
                    tvStatus.setText("● 启动失败");
                    tvStatus.setTextColor(0xFFD93025);
                    tvUrl.setText("失败请查看日志");
                    tvUrl.setTextColor(0xFFD93025);
                } else {
                    tvStatus.setText("● 未运行");
                    tvStatus.setTextColor(0xFF9AA0A6);
                    tvUrl.setText("暂无公网 URL");
                    tvUrl.setTextColor(0xFF9AA0A6);
                }
            } catch (Exception e) {
                tvStatus.setText("● 错误");
                tvStatus.setTextColor(0xFFD93025);
                tvUrl.setText("失败请查看日志");
                tvUrl.setTextColor(0xFFD93025);
            }
        };
        refreshAll.run();
        // 注册到全局让 broadcast 触发时刷新
        setMefrpSheetRefs(tvStatus, tvUrl, refreshAll);

        // ========== 点击事件（保持不变）==========
        btnStart.setOnClickListener(v -> {
            if (!com.zm920.androidserver.mefrp.MefrpConfig.hasRequiredConfig(requireContext())) {
                ToastUtil.showShort(requireContext(), "请先填写 Key 和隧道号");
                return;
            }
            Intent i = new Intent(requireContext(), MefrpService.class);
            i.setAction(MefrpService.ACTION_START);
            requireContext().startForegroundService(i);
            refreshAll.run();
            ToastUtil.showShort(requireContext(), "ME Frp 启动指令已发送");
        });
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), MefrpService.class);
            i.setAction(MefrpService.ACTION_STOP);
            requireContext().startService(i);
            refreshAll.run();
            ToastUtil.showShort(requireContext(), "已停止");
        });
        btnCopy.setOnClickListener(v -> {
            String url = tvUrl.getText().toString();
            if (url.isEmpty() || url.equals("暂无公网 URL")) {
                ToastUtil.showShort(requireContext(), "暂无可复制的 URL");
            } else {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("mefrp url", url));
                ToastUtil.showShort(requireContext(), "已复制");
            }
        });
        btnSave.setOnClickListener(v -> {
            try {
                com.zm920.androidserver.mefrp.MefrpConfig.setKey(requireContext(), etKey.getText().toString().trim());
                com.zm920.androidserver.mefrp.MefrpConfig.setTunnelPort(requireContext(), etTunnelPort.getText().toString().trim());
                String lpStr = etLocalPort.getText().toString().trim();
                int lp = lpStr.isEmpty() ? 8080 : Integer.parseInt(lpStr);
                if (lp <= 0 || lp > 65535) throw new Exception("本地端口无效");
                com.zm920.androidserver.mefrp.MefrpConfig.setLocalPort(requireContext(), lp);
                com.zm920.androidserver.mefrp.MefrpConfig.setAutoStart(requireContext(), switchAutoStart.isChecked());
                ToastUtil.showShort(requireContext(), "已保存");
            } catch (Exception e) {
                ToastUtil.showShort(requireContext(), "保存失败: " + e.getMessage());
            }
        });
        btnLog.setOnClickListener(v -> showMefrpLogSheet());
        btnCheck.setOnClickListener(v -> showMefrpCheckSheet());

        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.show();
    }

    /** 工具列表项：图标 + 文字 + 右箭头 */
    private LinearLayout makeToolItem(String icon, String text) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(16), dp2px(14), dp2px(16), dp2px(14));
        row.setClickable(true);
        row.setFocusable(true);

        TextView iconTv = new TextView(requireContext());
        iconTv.setText(icon);
        iconTv.setTextSize(16f);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                dp2px(28), LinearLayout.LayoutParams.WRAP_CONTENT);
        iconTv.setLayoutParams(iconLp);
        row.addView(iconTv);

        TextView textTv = new TextView(requireContext());
        textTv.setText(text);
        textTv.setTextSize(14f);
        textTv.setTextColor(0xFF202124);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textTv.setLayoutParams(textLp);
        row.addView(textTv);

        TextView arrow = new TextView(requireContext());
        arrow.setText("›");
        arrow.setTextSize(22f);
        arrow.setTextColor(0xFF9AA0A6);
        row.addView(arrow);

        return row;
    }


    /** mefrp 未部署时弹出的自动部署对话框：用户确认后自动部署，部署完成进入配置面板 */
    private void showMefrpAutoDeployDialog(com.google.android.material.bottomsheet.BottomSheetDialog parentSheet) {
        // 先检查本地是否有压缩包，决定提示文案
        java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        java.io.File localArchive = new java.io.File(downloadDir, "mefrpc.tar.gz");
        if (!localArchive.exists() || localArchive.length() == 0) {
            localArchive = new java.io.File("/storage/emulated/0/mefrpc.tar.gz");
        }
        boolean hasLocal = localArchive.exists() && localArchive.length() > 0;
        String msg = hasLocal
                ? "检测到本地 mefrpc.tar.gz，将自动部署到私有目录。"
                : "本地未找到 mefrpc.tar.gz，将从网络下载并部署到私有目录。";

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("自动部署 mefrpc")
                .setMessage(msg)
                .setPositiveButton("开始部署", (d, w) -> {
                    if (parentSheet != null) parentSheet.dismiss();
                    runMefrpAutoDeploy();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 执行 mefrpc 自动部署：完成后进入配置面板 */
    private void runMefrpAutoDeploy() {
        androidx.appcompat.app.AlertDialog progress = newThemeProgressDialog("部署中", "正在部署 mefrpc，请稍候...").show();
        new Thread(() -> {
            boolean ok = false;
            String err = null;
            try {
                java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File localArchive = new java.io.File(downloadDir, "mefrpc.tar.gz");
                if (!localArchive.exists() || localArchive.length() == 0) {
                    localArchive = new java.io.File("/storage/emulated/0/mefrpc.tar.gz");
                }
                java.io.File archiveToUse;
                if (localArchive.exists() && localArchive.length() > 0) {
                    archiveToUse = localArchive;
                } else {
                    java.io.File tmpDir = new java.io.File(requireContext().getFilesDir(), ".tmp");
                    tmpDir.mkdirs();
                    archiveToUse = new java.io.File(tmpDir, "mefrpc.tar.gz");
                    downloadMefrpBinary(com.zm920.androidserver.mefrp.MefrpConfig.DEFAULT_DOWNLOAD_URL, archiveToUse);
                }
                deployMefrpFromArchive(archiveToUse);
                ok = true;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final boolean finalOk = ok;
            final String finalErr = err;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                    if (finalOk) {
                        ToastUtil.showShort(requireContext(), "部署完成");
                        // 部署成功后直接进入配置面板
                        showMefrpBottomSheet();
                    } else {
                        ToastUtil.showLong(requireContext(), "部署失败: " + finalErr);
                    }
                });
            }
        }).start();
    }

    /** 初始化 mefrpc 二进制：自动读取本地压缩包，没有再走内置 URL 网络下载，部署到私有目录 */
    private void initMefrpBinary(Runnable refreshAll) {
        // 1) 检查私有目录是否已部署
        java.io.File binFile = com.zm920.androidserver.mefrp.MefrpManager.get(requireContext()).getBinFile();
        if (binFile.exists() && binFile.length() > 0) {
            // 确保有执行权限
            try { android.system.Os.chmod(binFile.getAbsolutePath(), 0755); } catch (Exception ignored) {}
            try { Runtime.getRuntime().exec(new String[]{"chmod", "755", binFile.getAbsolutePath()}).waitFor(); } catch (Exception ignored) {}
            ToastUtil.showShort(requireContext(), "已部署: " + binFile.getAbsolutePath());
            refreshAll.run();
            return;
        }

        // 2) 自动从公共下载目录读取压缩包
        java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        java.io.File localArchive = new java.io.File(downloadDir, "mefrpc.tar.gz");
        if (!localArchive.exists() || localArchive.length() == 0) {
            localArchive = new java.io.File("/storage/emulated/0/mefrpc.tar.gz");
        }

        java.io.File archiveToUse = null;
        boolean needDownload = false;
        if (localArchive.exists() && localArchive.length() > 0) {
            archiveToUse = localArchive;
        } else {
            // 3) 本地没有，使用内置 URL 下载到临时目录
            needDownload = true;
            java.io.File tmpDir = new java.io.File(requireContext().getFilesDir(), ".tmp");
            tmpDir.mkdirs();
            archiveToUse = new java.io.File(tmpDir, "mefrpc.tar.gz");
        }

        final java.io.File finalArchive = archiveToUse;
        final boolean finalNeedDownload = needDownload;
        androidx.appcompat.app.AlertDialog progress = newThemeProgressDialog("初始化中",
                finalNeedDownload ? "本地未找到 mefrpc.tar.gz，正在从网络下载..." : "正在部署 mefrpc，请稍候...").show();
        new Thread(() -> {
            boolean ok = false;
            String err = null;
            try {
                // 如果需要网络下载，先下载
                if (finalNeedDownload) {
                    String url = com.zm920.androidserver.mefrp.MefrpConfig.DEFAULT_DOWNLOAD_URL;
                    downloadMefrpBinary(url, finalArchive);
                }
                // 部署到私有目录
                deployMefrpFromArchive(finalArchive);
                ok = true;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final boolean finalOk = ok;
            final String finalErr = err;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                    if (finalOk) {
                        ToastUtil.showShort(requireContext(), "部署完成");
                    } else {
                        ToastUtil.showLong(requireContext(), "部署失败: " + finalErr);
                    }
                    refreshAll.run();
                });
            }
        }).start();
    }

    /** 从压缩包部署 mefrpc 到私有目录 */
    private void deployMefrpFromArchive(java.io.File archive) throws Exception {
        java.io.File deployDir = com.zm920.androidserver.mefrp.MefrpManager.get(requireContext()).getDeployDir();
        java.io.File binDir = new java.io.File(deployDir, "bin");
        if (deployDir.exists()) deleteDirForMefrp(deployDir);
        deployDir.mkdirs();
        binDir.mkdirs();

        // 判断是 tar.gz 还是单文件
        if (archive.getName().endsWith(".tar.gz")) {
            // 先解压到临时目录
            java.io.File tmpExtract = new java.io.File(requireContext().getFilesDir(), ".tmp/mefrpc-extract");
            if (tmpExtract.exists()) deleteDirForMefrp(tmpExtract);
            tmpExtract.mkdirs();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(archive);
                 java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(fis);
                 java.io.BufferedInputStream bis = new java.io.BufferedInputStream(gzis)) {
                extractTarToDir(bis, tmpExtract);
            }
            // 查找解压后的顶层目录（通常是 mefrpc-1.0/）
            java.io.File[] subs = tmpExtract.listFiles();
            java.io.File srcDir = tmpExtract;
            if (subs != null && subs.length == 1 && subs[0].isDirectory()) {
                srcDir = subs[0];
            }
            // 把 srcDir 里的内容移动到 deployDir
            java.io.File srcBin = new java.io.File(srcDir, "bin/mefrpc");
            if (!srcBin.exists()) {
                srcBin = findFileRecursive(srcDir, "mefrpc");
            }
            if (srcBin == null || !srcBin.exists()) {
                throw new Exception("压缩包内未找到 mefrpc 二进制");
            }
            java.io.File destBin = new java.io.File(binDir, "mefrpc");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(srcBin);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(destBin)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            }
            // 清理临时目录
            deleteDirForMefrp(tmpExtract);
        } else {
            // 单文件直接复制
            java.io.File dest = new java.io.File(binDir, "mefrpc");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(archive);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            }
        }

        // 验证二进制
        java.io.File binFile = new java.io.File(binDir, "mefrpc");
        if (!binFile.exists() || binFile.length() == 0) {
            throw new Exception("部署后二进制不存在或为空");
        }
        // 设置执行权限
        try { android.system.Os.chmod(binFile.getAbsolutePath(), 0755); } catch (Exception ignored) {}
        try { Runtime.getRuntime().exec(new String[]{"chmod", "755", binFile.getAbsolutePath()}).waitFor(); } catch (Exception ignored) {}
        android.util.Log.i("Settings", "mefrpc 部署完成: " + binFile.getAbsolutePath() + " size=" + binFile.length() + " exec=" + binFile.canExecute());
    }

    /** 递归查找文件 */
    private java.io.File findFileRecursive(java.io.File dir, String name) {
        if (!dir.isDirectory()) return null;
        java.io.File[] files = dir.listFiles();
        if (files == null) return null;
        for (java.io.File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                java.io.File found = findFileRecursive(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 递归删除目录（mefrpc 部署用） */
    private void deleteDirForMefrp(java.io.File dir) {
        if (!dir.exists()) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    /** 简易 tar 解压（只支持标准 tar 格式） */
    private void extractTarToDir(java.io.InputStream is, java.io.File destDir) throws Exception {
        byte[] block = new byte[512];
        while (true) {
            int read = readFully(is, block);
            if (read < 512) break;
            if (isAllZeros(block)) break;
            String fileName = new String(block, 0, 100, "ASCII").trim();
            int idx = fileName.indexOf(' ');
            if (idx >= 0) fileName = fileName.substring(0, idx);
            if (fileName.isEmpty()) break;
            char type = (char) block[156];
            long fileSize = Long.parseLong(new String(block, 124, 12, "ASCII").trim(), 8);
            if (type == 'L') {
                byte[] longNameBuf = new byte[(int) fileSize];
                readFully(is, longNameBuf);
                fileName = new String(longNameBuf, "ASCII").trim();
                idx = fileName.indexOf(' ');
                if (idx >= 0) fileName = fileName.substring(0, idx);
                skipPadding(is, fileSize);
                read = readFully(is, block);
                if (read < 512) break;
                if (isAllZeros(block)) break;
                type = (char) block[156];
                fileSize = Long.parseLong(new String(block, 124, 12, "ASCII").trim(), 8);
            }
            java.io.File outFile = new java.io.File(destDir, fileName);
            if (type == '5' || fileName.endsWith("/")) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                    long remaining = fileSize;
                    byte[] buf = new byte[8192];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = is.read(buf, 0, toRead);
                        if (n < 0) break;
                        fos.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                try { android.system.Os.chmod(outFile.getAbsolutePath(), 0755); } catch (Exception ignored) {}
            }
            skipPadding(is, fileSize);
        }
    }

    private int readFully(java.io.InputStream is, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int n = is.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private boolean isAllZeros(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private void skipPadding(java.io.InputStream is, long fileSize) throws Exception {
        long padding = (512 - (fileSize % 512)) % 512;
        long skipped = 0;
        while (skipped < padding) {
            long s = is.skip(padding - skipped);
            if (s <= 0) break;
            skipped += s;
        }
    }

    /** 下载 mefrpc 压缩包 */
    private void downloadMefrpBinary(String urlStr, java.io.File dest) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        int code = conn.getResponseCode();
        if (code != 200 && code != 201) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        try (java.io.InputStream is = new java.io.BufferedInputStream(conn.getInputStream());
             java.io.FileOutputStream os = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        } finally {
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void showMefrpCheckSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));
        TextView title = new TextView(requireContext());
        title.setText("ME Frp 环境自检");
        title.setTextSize(16f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(8);
        title.setLayoutParams(lp);
        root.addView(title);
        TextView tv = new TextView(requireContext());
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        try {
            tv.setText(com.zm920.androidserver.mefrp.MefrpManager.get(requireContext()).selfCheck());
        } catch (Exception e) {
            tv.setText("自检失败: " + e.getMessage());
        }
        tv.setTextIsSelectable(true);
        root.addView(tv);
        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.show();
    }

    private void showMefrpLogSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(20);
        root.setPadding(pad, pad, pad, dp2px(28));
        TextView title = new TextView(requireContext());
        title.setText("ME Frp 日志");
        title.setTextSize(16f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(8);
        title.setLayoutParams(lp);
        root.addView(title);
        TextView tv = new TextView(requireContext());
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        try {
            tv.setText(com.zm920.androidserver.mefrp.MefrpManager.get(requireContext()).readTailLog(20 * 1024));
        } catch (Exception e) {
            tv.setText("读取失败: " + e.getMessage());
        }
        tv.setTextIsSelectable(true);
        root.addView(tv);
        ScrollView sv = new ScrollView(requireContext());
        sv.addView(root);
        sheet.setContentView(sv);
        sheet.show();
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF5F6368);
        tv.setPadding(0, dp2px(6), 0, dp2px(4));
        parent.addView(tv);
    }

    private EditText makeEdit() {
        EditText e = new EditText(requireContext());
        e.setBackgroundColor(0xFFF1F3F4);
        e.setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp2px(8);
        e.setLayoutParams(lp);
        e.setSingleLine(true);
        return e;
    }

    private TextView bigButton(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(getThemeAccentColor());
        return t;
    }

    private TextView smallAction(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(getThemeAccentColor());
        t.setPadding(0, dp2px(8), 0, dp2px(8));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private View spacer(int w) {
        View v = new View(requireContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private android.content.Context getAppContext() {
        return requireContext().getApplicationContext();
    }

    // ---- 主题色弹窗（与 DownloadFragment 风格一致） ----

    private static final int ID_THEMED_CONTENT = 0x7F0A9999;

    private static class ThemedDialogBuilder {
        final android.app.Dialog dialog;
        final android.widget.LinearLayout root;
        final android.widget.LinearLayout btnRow;
        final int primaryColor;
        final int r, g, b;
        final float density;

        ThemedDialogBuilder(android.content.Context ctx) {
            primaryColor = resolveColor(ctx);
            r = android.graphics.Color.red(primaryColor);
            g = android.graphics.Color.green(primaryColor);
            b = android.graphics.Color.blue(primaryColor);
            density = ctx.getResources().getDisplayMetrics().density;

            dialog = new android.app.Dialog(ctx);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            root = new android.widget.LinearLayout(ctx);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFFFFFFFF);

            android.widget.LinearLayout titleBar = new android.widget.LinearLayout(ctx);
            titleBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            titleBar.setBackgroundColor(primaryColor);
            int tPad = (int) (14 * density);
            titleBar.setPadding(tPad, tPad, tPad, tPad);
            android.widget.LinearLayout.LayoutParams titleBarLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            titleBar.setLayoutParams(titleBarLp);
            root.addView(titleBar);

            android.widget.LinearLayout contentBox = new android.widget.LinearLayout(ctx);
            contentBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            int cPad = (int) (16 * density);
            contentBox.setPadding(cPad, cPad, cPad, cPad);
            android.widget.LinearLayout.LayoutParams contentLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            contentBox.setLayoutParams(contentLp);
            contentBox.setId(ID_THEMED_CONTENT);
            root.addView(contentBox);

            btnRow = new android.widget.LinearLayout(ctx);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            int bPad = (int) (12 * density);
            android.widget.LinearLayout.LayoutParams btnRowLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            btnRowLp.leftMargin = bPad;
            btnRowLp.rightMargin = bPad;
            btnRowLp.bottomMargin = bPad;
            btnRow.setLayoutParams(btnRowLp);
            root.addView(btnRow);
        }

        ThemedDialogBuilder setCancelable(boolean cancelable) {
            dialog.setCancelable(cancelable);
            return this;
        }

        ThemedDialogBuilder setTitle(String title) {
            android.widget.LinearLayout titleBar = (android.widget.LinearLayout) root.getChildAt(0);
            android.widget.TextView titleTv = new android.widget.TextView(dialog.getContext());
            titleTv.setText(title);
            titleTv.setTextSize(15f);
            titleTv.setTextColor(0xFFFFFFFF);
            titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
            titleTv.setSingleLine(true);
            titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleTv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            titleBar.addView(titleTv);
            return this;
        }

        ThemedDialogBuilder setContentView(android.view.View v) {
            android.widget.LinearLayout contentBox = (android.widget.LinearLayout) root.findViewById(ID_THEMED_CONTENT);
            contentBox.removeAllViews();
            contentBox.addView(v);
            return this;
        }

        ThemedDialogBuilder setMessage(String text) {
            android.widget.TextView tv = new android.widget.TextView(dialog.getContext());
            tv.setText(text);
            tv.setTextSize(14f);
            tv.setTextColor(0xFF333333);
            tv.setLineSpacing(2f, 1f);
            return setContentView(tv);
        }

        ThemedDialogBuilder addButton(String text, boolean primary, final java.util.function.Consumer<android.app.Dialog> onClick) {
            android.widget.TextView btn = new android.widget.TextView(dialog.getContext());
            btn.setText(text);
            btn.setTextSize(14f);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setClickable(true);
            btn.setFocusable(true);
            int vPad = (int) (10 * density);
            btn.setPadding((int) (12 * density), vPad, (int) (12 * density), vPad);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(8 * density);
            if (primary) {
                bg.setColor(primaryColor);
                btn.setTextColor(0xFFFFFFFF);
            } else {
                bg.setColor(android.graphics.Color.argb(18, r, g, b));
                bg.setStroke((int) (1 * density), android.graphics.Color.argb(120, r, g, b));
                btn.setTextColor(primaryColor);
            }
            btn.setBackground(bg);

            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = (int) (4 * density);
            lp.rightMargin = (int) (4 * density);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                if (onClick != null) onClick.accept(dialog);
            });
            btnRow.addView(btn);
            return this;
        }

        android.app.Dialog show() {
            dialog.setContentView(root);
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(
                        (int) (dialog.getContext().getResources().getDisplayMetrics().widthPixels * 0.88f),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
                winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                winBg.setCornerRadius(14 * density);
                winBg.setColor(0xFFFFFFFF);
                window.setBackgroundDrawable(winBg);
                window.getDecorView().setBackgroundColor(0xFFFFFFFF);
            }
            dialog.show();
            return dialog;
        }
    }

    private static int resolveColor(android.content.Context ctx) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("server_settings", 0);
            int resId;
            switch (p.getString("theme_key", "slate")) {
                case "green":  resId = com.zm920.androidserver.R.color.theme_green_primary; break;
                case "purple": resId = com.zm920.androidserver.R.color.theme_purple_primary; break;
                case "orange": resId = com.zm920.androidserver.R.color.theme_orange_primary; break;
                case "blue":   resId = com.zm920.androidserver.R.color.blue_primary; break;
                case "red":    resId = com.zm920.androidserver.R.color.theme_red_primary; break;
                case "cyan":   resId = com.zm920.androidserver.R.color.theme_cyan_primary; break;
                case "pink":   resId = com.zm920.androidserver.R.color.theme_pink_primary; break;
                case "indigo": resId = com.zm920.androidserver.R.color.theme_indigo_primary; break;
                case "brown":  resId = com.zm920.androidserver.R.color.theme_brown_primary; break;
                default:       resId = com.zm920.androidserver.R.color.theme_slate_primary;
            }
            return androidx.core.content.ContextCompat.getColor(ctx, resId);
        } catch (Exception e) {
            return 0xFF1A73E8;
        }
    }

}
