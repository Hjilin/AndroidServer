package com.zm920.androidserver.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.zm920.androidserver.R;
import com.zm920.androidserver.network.NetworkUtil;
import com.zm920.androidserver.server.WebdavServer;
import com.zm920.androidserver.service.FtpServerService;
import com.zm920.androidserver.service.WsServerService;
import com.zm920.androidserver.ui.widget.ToastUtil;

import java.io.File;

public class SitesFragment extends Fragment {

    // WebDAV
    private View dotWebdav;
    private android.widget.TextView tvWebdavStatus;
    private SwitchMaterial switchWebdav;
    private boolean syncingWebdav = false;

    // FTP
    private View dotFtp;
    private android.widget.TextView tvFtpStatus;
    private SwitchMaterial switchFtp;
    private boolean syncingFtp = false;

    // WebSocket
    private View dotWs;
    private android.widget.TextView tvWsStatus;
    private SwitchMaterial switchWs;
    private boolean syncingWs = false;

    private android.content.SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sites, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);

        // ===== WebDAV =====
        dotWebdav = view.findViewById(R.id.dot_sites_webdav);
        tvWebdavStatus = view.findViewById(R.id.tv_sites_webdav_status);
        switchWebdav = view.findViewById(R.id.switch_sites_webdav);
        view.findViewById(R.id.btn_sites_webdav_start).setOnClickListener(v -> startWebdav());
        view.findViewById(R.id.btn_sites_webdav_stop).setOnClickListener(v -> stopWebdav());
        view.findViewById(R.id.btn_sites_webdav_log).setOnClickListener(v -> showWebdavLog());
        view.findViewById(R.id.btn_sites_webdav_config).setOnClickListener(v -> showWebdavConfig());
        switchWebdav.setOnCheckedChangeListener((bv, checked) -> {
            if (syncingWebdav) return;
            if (checked) startWebdav(); else stopWebdav();
        });

        // ===== FTP =====
        dotFtp = view.findViewById(R.id.dot_sites_ftp);
        tvFtpStatus = view.findViewById(R.id.tv_sites_ftp_status);
        switchFtp = view.findViewById(R.id.switch_sites_ftp);
        view.findViewById(R.id.btn_sites_ftp_start).setOnClickListener(v -> startFtp());
        view.findViewById(R.id.btn_sites_ftp_stop).setOnClickListener(v -> stopFtp());
        view.findViewById(R.id.btn_sites_ftp_log).setOnClickListener(v -> showFtpLog());
        switchFtp.setOnCheckedChangeListener((bv, checked) -> {
            if (syncingFtp) return;
            if (checked) startFtp(); else stopFtp();
        });

        // ===== WebSocket =====
        dotWs = view.findViewById(R.id.dot_sites_ws);
        tvWsStatus = view.findViewById(R.id.tv_sites_ws_status);
        switchWs = view.findViewById(R.id.switch_sites_ws);
        view.findViewById(R.id.btn_sites_ws_start).setOnClickListener(v -> startWs());
        view.findViewById(R.id.btn_sites_ws_stop).setOnClickListener(v -> stopWs());
        view.findViewById(R.id.btn_sites_ws_log).setOnClickListener(v -> showWsLog());
        switchWs.setOnCheckedChangeListener((bv, checked) -> {
            if (syncingWs) return;
            if (checked) startWs(); else stopWs();
        });

        refreshAll();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAll();
    }

    // ===== 状态刷新 =====
    private void refreshAll() {
        // WebDAV
        boolean wdRunning = WebdavServer.isRunning();
        syncingWebdav = true;
        try { if (switchWebdav != null && switchWebdav.isChecked() != wdRunning) switchWebdav.setChecked(wdRunning); }
        finally { syncingWebdav = false; }
        if (dotWebdav != null)
            dotWebdav.setBackgroundResource(wdRunning ? R.drawable.circle_green : R.drawable.circle_gray);
        if (tvWebdavStatus != null) {
            if (wdRunning) tvWebdavStatus.setText("运行中 · " + webdavUrl() + " · 根目录 " + webdavRoot());
            else tvWebdavStatus.setText("未启动 · 端口 " + webdavPort());
        }

        // FTP
        boolean ftpRunning = FtpServerService.isRunning();
        syncingFtp = true;
        try { if (switchFtp != null && switchFtp.isChecked() != ftpRunning) switchFtp.setChecked(ftpRunning); }
        finally { syncingFtp = false; }
        if (dotFtp != null)
            dotFtp.setBackgroundResource(ftpRunning ? R.drawable.circle_green : R.drawable.circle_gray);
        if (tvFtpStatus != null) {
            if (ftpRunning) {
                tvFtpStatus.setText("运行中 · 端口 " + FtpServerService.getCurrentPort());
            } else {
                tvFtpStatus.setText("未启动 · 端口 " + prefs.getInt("ftp_port", 2121) + "（需在设置配置用户）");
            }
        }

        // WS
        boolean wsRunning = WsServerService.isRunning();
        syncingWs = true;
        try { if (switchWs != null && switchWs.isChecked() != wsRunning) switchWs.setChecked(wsRunning); }
        finally { syncingWs = false; }
        if (dotWs != null)
            dotWs.setBackgroundResource(wsRunning ? R.drawable.circle_green : R.drawable.circle_gray);
        if (tvWsStatus != null) {
            if (wsRunning) {
                tvWsStatus.setText("运行中 · ws://" + (NetworkUtil.getLocalIpAddress() == null ? "手机IP" : NetworkUtil.getLocalIpAddress()) + ":" + WsServerService.getCurrentPort());
            } else {
                tvWsStatus.setText("未启动 · 端口 " + WsServerService.getConfiguredPort(requireContext()));
            }
        }
    }

    // ===== WebDAV =====
    private int webdavPort() { return prefs.getInt("webdav_port", 5309); }
    private String webdavRoot() {
        String r = prefs.getString("webdav_root", "");
        if (r == null || r.isEmpty()) {
            r = new File(requireContext().getFilesDir(), "wwwroot/www").getAbsolutePath();
        }
        return r;
    }
    private String webdavPassword() {
        String p = prefs.getString("webdav_password", "");
        return p == null ? "" : p;
    }
    private String webdavUrl() {
        String ip = NetworkUtil.getLocalIpAddress();
        return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + webdavPort();
    }

    private void startWebdav() {
        tvWebdavStatus.setText("正在启动…");
        new Thread(() -> {
            boolean ok = WebdavServer.start(requireContext(), webdavPort(), webdavRoot(), webdavPassword());
            if (isAdded()) getActivity().runOnUiThread(() -> {
                if (!ok) ToastUtil.showShort(getContext(), "WebDAV 启动失败，见日志");
                refreshAll();
            });
        }).start();
    }

    private void stopWebdav() {
        new Thread(() -> {
            WebdavServer.stop();
            if (isAdded()) getActivity().runOnUiThread(this::refreshAll);
        }).start();
    }

    private void showWebdavConfig() {
        new android.app.Dialog(requireContext());
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle("WebDAV 配置");
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);
        float density = getResources().getDisplayMetrics().density;

        android.widget.TextView hint = new android.widget.TextView(requireContext());
        hint.setText("用电脑浏览器或文件管理器挂载 WebDAV，即可访问手机文件（类似网盘）。");
        hint.setTextSize(12f);
        hint.setTextColor(getResources().getColor(R.color.text_secondary, null));
        root.addView(hint);

        android.widget.TextView portLabel = new android.widget.TextView(requireContext());
        portLabel.setText("端口");
        portLabel.setTextSize(14f);
        portLabel.setTextColor(getResources().getColor(R.color.text_primary, null));
        portLabel.setPadding(0, pad, 0, 4);
        root.addView(portLabel);
        final android.widget.EditText etPort = new android.widget.EditText(requireContext());
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(webdavPort()));
        root.addView(etPort);

        android.widget.TextView rootLabel = new android.widget.TextView(requireContext());
        rootLabel.setText("共享根目录（留空=默认 wwwroot）");
        rootLabel.setTextSize(14f);
        rootLabel.setTextColor(getResources().getColor(R.color.text_primary, null));
        rootLabel.setPadding(0, pad, 0, 4);
        root.addView(rootLabel);
        final android.widget.EditText etRoot = new android.widget.EditText(requireContext());
        etRoot.setText(webdavRoot());
        root.addView(etRoot);

        android.widget.TextView passLabel = new android.widget.TextView(requireContext());
        passLabel.setText("访问密码（留空=免密）");
        passLabel.setTextSize(14f);
        passLabel.setTextColor(getResources().getColor(R.color.text_primary, null));
        passLabel.setPadding(0, pad, 0, 4);
        root.addView(passLabel);
        final android.widget.EditText etPass = new android.widget.EditText(requireContext());
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setText(webdavPassword());
        root.addView(etPass);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams brp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        brp.topMargin = pad;
        brp.bottomMargin = pad;
        btnRow.setLayoutParams(brp);

        android.widget.TextView cancelBtn = new android.widget.TextView(requireContext());
        cancelBtn.setText("取消");
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextSize(14f);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cbg.setCornerRadius(pad);
        cbg.setStroke((int) (1 * density), 0xFFCCCCCC);
        cancelBtn.setBackground(cbg);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (44 * density), 1);
        clp.rightMargin = pad / 2;
        cancelBtn.setLayoutParams(clp);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        android.widget.TextView okBtn = new android.widget.TextView(requireContext());
        okBtn.setText("保存");
        okBtn.setGravity(android.view.Gravity.CENTER);
        okBtn.setTextSize(14f);
        okBtn.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable obg = new android.graphics.drawable.GradientDrawable();
        obg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        obg.setCornerRadius(pad);
        obg.setColor(0xFFFB7A3D);
        okBtn.setBackground(obg);
        android.widget.LinearLayout.LayoutParams olp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (44 * density), 1);
        olp.leftMargin = pad / 2;
        okBtn.setLayoutParams(olp);
        okBtn.setOnClickListener(v -> {
            String p = etPort.getText().toString().trim();
            int port;
            try { port = Integer.parseInt(p); if (port <= 0 || port > 65535) port = 5309; }
            catch (Exception e) { port = 5309; }
            prefs.edit().putInt("webdav_port", port)
                .putString("webdav_root", etRoot.getText().toString().trim())
                .putString("webdav_password", etPass.getText().toString()).apply();
            dialog.dismiss();
            ToastUtil.showShort(getContext(), "已保存，需停止后重新启动生效");
            refreshAll();
        });
        btnRow.addView(okBtn);
        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }

    private void showWebdavLog() {
        showLogDialog("WebDAV 日志", WebdavServer.readLog(requireContext()),
                () -> WebdavServer.readLog(requireContext()),
                () -> { WebdavServer.clearLog(requireContext()); return ""; },
                "webdav.log");
    }

    // ===== FTP =====
    private void startFtp() {
        if (FtpServerService.isRunning()) { ToastUtil.showShort(getContext(), "FTP 已在运行"); return; }
        String users = prefs.getString("ftp_users", "");
        if (users == null || users.isEmpty() || "[]".equals(users)) {
            ToastUtil.showLong(getContext(), "请先在「设置」中配置 FTP 端口和用户");
            refreshAll();
            return;
        }
        try {
            requireContext().startForegroundService(new Intent(requireContext(), FtpServerService.class));
            ToastUtil.showShort(getContext(), "FTP 启动中...");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshAll, 800);
        } catch (Exception e) {
            ToastUtil.showShort(getContext(), "启动失败: " + e.getMessage());
        }
    }

    private void stopFtp() {
        FtpServerService.stopFtp(requireContext());
        ToastUtil.showShort(getContext(), "FTP 已停止");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshAll, 500);
    }

    private void showFtpLog() {
        showLogDialog("FTP 日志", FtpServerService.readLog(requireContext()),
                () -> FtpServerService.readLog(requireContext()),
                () -> { FtpServerService.clearLog(requireContext()); return ""; },
                "ftp.log");
    }

    // ===== WebSocket =====
    private void startWs() {
        if (WsServerService.isRunning()) { ToastUtil.showShort(getContext(), "WS 已在运行"); return; }
        int port = WsServerService.getConfiguredPort(requireContext());
        Intent i = new Intent(requireContext(), WsServerService.class);
        i.setAction(WsServerService.ACTION_START);
        i.putExtra(WsServerService.EXTRA_PORT, port);
        try {
            requireContext().startForegroundService(i);
            ToastUtil.showShort(getContext(), "WS 启动中...");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshAll, 800);
        } catch (Exception e) {
            ToastUtil.showShort(getContext(), "启动失败: " + e.getMessage());
        }
    }

    private void stopWs() {
        Intent i = new Intent(requireContext(), WsServerService.class);
        i.setAction(WsServerService.ACTION_STOP);
        requireContext().startService(i);
        ToastUtil.showShort(getContext(), "WS 已停止");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshAll, 500);
    }

    private void showWsLog() {
        showLogDialog("WebSocket 日志", WsServerService.readLog(requireContext()),
                () -> WsServerService.readLog(requireContext()),
                () -> { WsServerService.clearLog(requireContext()); return ""; },
                "ws.log");
    }

    // ===== 通用日志弹窗 =====
    private void showLogDialog(String title, String initial, LogReader reader, LogClear clearer, String fileName) {
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle(title);
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);
        float density = getResources().getDisplayMetrics().density;

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF1E1E1E);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * density));
        scroll.setLayoutParams(scrollLp);
        final android.widget.TextView logTv = new android.widget.TextView(requireContext());
        logTv.setTextSize(11f);
        logTv.setTextColor(0xFFD4D4D4);
        logTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        logTv.setPadding(pad, pad, pad, pad);
        logTv.setText(initial);
        scroll.addView(logTv);
        root.addView(scroll);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams btnRowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = pad;
        btnRowLp.bottomMargin = pad;
        btnRow.setLayoutParams(btnRowLp);

        android.widget.TextView refreshBtn = new android.widget.TextView(requireContext());
        refreshBtn.setText("刷新");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setTextSize(13f);
        refreshBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable rbg = new android.graphics.drawable.GradientDrawable();
        rbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        rbg.setCornerRadius(pad);
        rbg.setColor(0xFFFB7A3D);
        refreshBtn.setBackground(rbg);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        rlp.rightMargin = pad / 2;
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setOnClickListener(v -> logTv.setText(reader.read()));
        btnRow.addView(refreshBtn);

        android.widget.TextView clearBtn = new android.widget.TextView(requireContext());
        clearBtn.setText("清空");
        clearBtn.setTextColor(0xFFFB7A3D);
        clearBtn.setTextSize(13f);
        clearBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cbg.setCornerRadius(pad);
        cbg.setColor(Color.argb(18, 251, 122, 61));
        cbg.setStroke((int) (1 * density), Color.argb(80, 251, 122, 61));
        clearBtn.setBackground(cbg);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        clp.leftMargin = pad / 2;
        clp.rightMargin = pad / 2;
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> { logTv.setText(clearer.clear()); ToastUtil.showShort(getContext(), "日志已清空"); });
        btnRow.addView(clearBtn);

        android.widget.TextView exportBtn = new android.widget.TextView(requireContext());
        exportBtn.setText("导出");
        exportBtn.setTextColor(0xFFFFFFFF);
        exportBtn.setTextSize(13f);
        exportBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ebg = new android.graphics.drawable.GradientDrawable();
        ebg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        ebg.setCornerRadius(pad);
        ebg.setColor(0xFFFB7A3D);
        exportBtn.setBackground(ebg);
        android.widget.LinearLayout.LayoutParams elp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        elp.leftMargin = pad / 2;
        exportBtn.setLayoutParams(elp);
        exportBtn.setOnClickListener(v -> {
            try {
                File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, fileName);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                    fo.write(logTv.getText().toString().getBytes("UTF-8"));
                }
                ToastUtil.showShort(getContext(), "已导出到下载目录 " + fileName);
            } catch (Exception e) {
                ToastUtil.showShort(getContext(), "导出失败: " + e.getMessage());
            }
        });
        btnRow.addView(exportBtn);

        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }

    interface LogReader { String read(); }
    interface LogClear { String clear(); }
}
