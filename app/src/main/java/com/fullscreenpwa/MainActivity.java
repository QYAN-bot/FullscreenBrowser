package com.fullscreenpwa;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "FullscreenPWA";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int ICON_PICKER_REQUEST = 1003;

    private static final String PREFS_NAME = "fullscreen_pwa_prefs";
    private static final String KEY_HOME_URL = "home_url";
    private static final String KEY_BOOKMARKS_JSON = "bookmarks_json";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_FIRST_RUN = "first_run_done";
    private static final String KEY_BTN_X = "toggle_btn_x";
    private static final String KEY_BTN_Y = "toggle_btn_y";
    private static final String KEY_BTN_SIZE = "toggle_btn_size";
    private static final String KEY_BTN_ALPHA = "toggle_btn_alpha";
    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private LinearLayout toolbar;
    private boolean toolbarVisible = true;
    private ImageButton btnToggle;
    private PermissionRequest pendingPermissionRequest;
    private ValueCallback<Uri[]> fileUploadCallback;
    private SharedPreferences prefs;

    private long lastBackPressTime = 0;
    private static final long BACK_PRESS_INTERVAL = 2000;

    // ============ Bookmark data class ============

    static class Bookmark {
        String url;
        String label;  // 用户自定义备注

        Bookmark(String url, String label) {
            this.url = url;
            this.label = (label != null) ? label : "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        migrateOldBookmarks(); // 把旧版纯 URL 书签迁移到新格式

        Window window = getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            // Android 11+ 用 ALWAYS（最强模式），低版本用 SHORT_EDGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            window.setAttributes(lp);
        }

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.activity_main);
        AndroidBug5497Workaround.assistActivity(this);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                    | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        applyStatusBarVisibility(prefs.getBoolean("show_status_bar", false));

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        toolbar = findViewById(R.id.toolbar);
        ImageButton btnGo = findViewById(R.id.btnGo);
        ImageButton btnBookmarks = findViewById(R.id.btnBookmarks);
        btnToggle = findViewById(R.id.btnToggle);

        setupWebView();

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadUrl(urlBar.getText().toString().trim());
                return true;
            }
            return false;
        });

        btnGo.setOnClickListener(v -> loadUrl(urlBar.getText().toString().trim()));
        btnBookmarks.setOnClickListener(v -> showBookmarksDialog());
        btnToggle.setOnClickListener(v -> {
            toolbarVisible = !toolbarVisible;
            toolbar.setVisibility(toolbarVisible ? View.VISIBLE : View.GONE);
        });

        // ★ 应用保存的按钮设置
        applyToggleButtonSettings();

        // ★ 长按拖动
        setupToggleDrag();

        // 首次运行引导
        boolean firstRunDone = prefs.getBoolean(KEY_FIRST_RUN, false);
        if (!firstRunDone) {
            showFirstRunSetup();
        } else {
            String homeUrl = prefs.getString(KEY_HOME_URL, "");
            if (homeUrl.isEmpty()) {
                showFirstRunSetup();
            } else {
                loadUrl(homeUrl);
            }
        }
    }

    // ============ 首次运行引导 ============

    private void showFirstRunSetup() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        TextView title = new TextView(this);
        title.setText("🚀 欢迎使用全屏浏览器");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("解决安卓刘海/挖孔屏全屏显示时顶部状态栏黑条问题");
        subtitle.setTextColor(Color.parseColor("#AAAAAA"));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        root.addView(subtitle);

        // 公告信息
        TextView authorInfo = new TextView(this);
        authorInfo.setText("作者：Claude Opus 4.6 × QYAN\n本软件免费公开分享，禁止倒卖");
        authorInfo.setTextColor(Color.parseColor("#999999"));
        authorInfo.setTextSize(11);
        root.addView(authorInfo);

        TextView githubLink = new TextView(this);
        githubLink.setText("GitHub: https://github.com/QYAN-bot/FullscreenBrowser");
        githubLink.setTextColor(Color.parseColor("#5599DD"));
        githubLink.setTextSize(11);
        githubLink.setPadding(0, dp(2), 0, dp(8));
        root.addView(githubLink);

        addDivider(root);

        TextView homeLabel = new TextView(this);
        homeLabel.setText("请设置你的默认主页：");
        homeLabel.setTextColor(Color.WHITE);
        homeLabel.setTextSize(14);
        homeLabel.setPadding(0, dp(12), 0, dp(8));
        root.addView(homeLabel);

        EditText homeInput = new EditText(this);
        homeInput.setHint("例如: https://example.com");
        homeInput.setTextColor(Color.WHITE);
        homeInput.setHintTextColor(Color.parseColor("#666666"));
        homeInput.setTextSize(14);
        homeInput.setSingleLine(true);
        homeInput.setBackgroundColor(Color.parseColor("#2A2A2A"));
        homeInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        homeInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(homeInput);

        TextView hint = new TextView(this);
        hint.setText("💡 之后可以在地址栏旁的 ★ 按钮里随时更改主页");
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        addDivider(root);

        TextView featTitle = new TextView(this);
        featTitle.setText("功能一览");
        featTitle.setTextColor(Color.parseColor("#888888"));
        featTitle.setTextSize(12);
        featTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(featTitle);

        String[] features = {
            "📱 全面屏显示 — 内容延伸到挖孔区",
            "🔖 书签收藏 — 地址栏旁 ★ 按钮，支持自定义备注",
            "📁 文件上传下载 — 支持大文件和 blob",
            "🏠 自定义主页 — 随时在书签面板切换",
            "🎨 自定义图标 — 在书签面板中创建桌面快捷方式",
            "↩️ 防误退 — 需双击返回键才退出",
        };
        for (String f : features) {
            TextView ft = new TextView(this);
            ft.setText(f);
            ft.setTextColor(Color.parseColor("#CCCCCC"));
            ft.setTextSize(12);
            ft.setPadding(0, dp(2), 0, dp(2));
            root.addView(ft);
        }

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(root)
            .setCancelable(false)
            .setPositiveButton("开始使用", null)
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url = homeInput.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(this, "请输入一个网址作为主页", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                setHomeUrl(url);
                prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply();
                dialog.dismiss();
                loadUrl(url);
            });
        });

        dialog.show();
    }

    // ============ 书签系统（带备注） ============

    /** 从旧版纯 URL Set 迁移到新版 JSON */
    private void migrateOldBookmarks() {
        if (prefs.contains("bookmarks") && !prefs.contains(KEY_BOOKMARKS_JSON)) {
            try {
                java.util.Set<String> oldSet = prefs.getStringSet("bookmarks",
                    new java.util.HashSet<>());
                JSONArray arr = new JSONArray();
                for (String url : oldSet) {
                    JSONObject obj = new JSONObject();
                    obj.put("url", url);
                    obj.put("label", "");
                    arr.put(obj);
                }
                prefs.edit()
                    .putString(KEY_BOOKMARKS_JSON, arr.toString())
                    .remove("bookmarks")
                    .apply();
            } catch (Exception e) {
                Log.e(TAG, "Bookmark migration failed: " + e.getMessage());
            }
        }
    }

    private List<Bookmark> getBookmarks() {
        List<Bookmark> list = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_BOOKMARKS_JSON, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new Bookmark(obj.getString("url"),
                    obj.optString("label", "")));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load bookmarks: " + e.getMessage());
        }
        return list;
    }

    private void saveBookmarks(List<Bookmark> bookmarks) {
        try {
            JSONArray arr = new JSONArray();
            for (Bookmark bm : bookmarks) {
                JSONObject obj = new JSONObject();
                obj.put("url", bm.url);
                obj.put("label", bm.label);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_BOOKMARKS_JSON, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save bookmarks: " + e.getMessage());
        }
    }

    private void addBookmark(String url, String label) {
        List<Bookmark> list = getBookmarks();
        // 不重复添加
        for (Bookmark bm : list) {
            if (bm.url.equals(url)) return;
        }
        list.add(new Bookmark(url, label));
        saveBookmarks(list);
    }

    private void removeBookmark(String url) {
        List<Bookmark> list = getBookmarks();
        list.removeIf(bm -> bm.url.equals(url));
        saveBookmarks(list);
    }

    private void updateBookmarkLabel(String url, String newLabel) {
        List<Bookmark> list = getBookmarks();
        for (Bookmark bm : list) {
            if (bm.url.equals(url)) {
                bm.label = newLabel;
                break;
            }
        }
        saveBookmarks(list);
    }

    private Bookmark findBookmark(String url) {
        for (Bookmark bm : getBookmarks()) {
            if (bm.url.equals(url)) return bm;
        }
        return null;
    }

    private String getHomeUrl() {
        return prefs.getString(KEY_HOME_URL, "");
    }

    private void setHomeUrl(String url) {
        prefs.edit().putString(KEY_HOME_URL, url).apply();
    }

    // ============ 书签面板 ============

    private void showBookmarksDialog() {
        String currentUrl = webView.getUrl();
        List<Bookmark> bookmarks = getBookmarks();
        String homeUrl = getHomeUrl();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(8));
        // 当前页面操作区
        TextView currentLabel = new TextView(this);
        currentLabel.setText("当前页面");
        currentLabel.setTextColor(Color.parseColor("#888888"));
        currentLabel.setTextSize(12);
        root.addView(currentLabel);

        TextView currentUrlText = new TextView(this);
        String displayUrl = currentUrl != null ? currentUrl : "无";
        if (displayUrl.length() > 50) displayUrl = displayUrl.substring(0, 50) + "...";
        currentUrlText.setText(displayUrl);
        currentUrlText.setTextColor(Color.WHITE);
        currentUrlText.setTextSize(13);
        currentUrlText.setPadding(0, dp(2), 0, dp(8));
        root.addView(currentUrlText);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        boolean isBookmarked = currentUrl != null && findBookmark(currentUrl) != null;
        TextView btnSave = makeActionButton(isBookmarked ? "★ 已收藏" : "☆ 收藏当前页");
        btnSave.setTextColor(isBookmarked ? Color.parseColor("#FFC107") : Color.WHITE);
        btnRow.addView(btnSave);
        addSpacer(btnRow, dp(8));

        boolean isHome = currentUrl != null && currentUrl.equals(homeUrl);
        TextView btnSetHome = makeActionButton(isHome ? "🏠 当前主页" : "🏠 设为主页");
        btnSetHome.setTextColor(isHome ? Color.parseColor("#4CAF50") : Color.WHITE);
        btnRow.addView(btnSetHome);

        root.addView(btnRow);

        addDivider(root);

        // 收藏列表
        TextView listLabel = new TextView(this);
        listLabel.setText("收藏夹 (" + bookmarks.size() + ")");
        listLabel.setTextColor(Color.parseColor("#888888"));
        listLabel.setTextSize(12);
        listLabel.setPadding(0, 0, 0, dp(4));
        root.addView(listLabel);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        scrollView.setLayoutParams(scrollLP);

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        AlertDialog[] dialogHolder = new AlertDialog[1];

        if (bookmarks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有收藏，点击上方「收藏当前页」添加");
            empty.setTextColor(Color.parseColor("#666666"));
            empty.setTextSize(13);
            empty.setPadding(0, dp(16), 0, dp(16));
            empty.setGravity(Gravity.CENTER);
            listContainer.addView(empty);
        } else {
            for (Bookmark bm : bookmarks) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));

                // 上半部分：备注/标签 + 操作按钮
                LinearLayout topRow = new LinearLayout(this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                boolean bmIsHome = bm.url.equals(homeUrl);

                // 显示名：有备注用备注，没有就截短 URL
                TextView nameText = new TextView(this);
                String displayName;
                if (!bm.label.isEmpty()) {
                    displayName = bm.label;
                } else {
                    displayName = bm.url;
                    // 去掉 https:// 前缀让显示更紧凑
                    displayName = displayName.replaceFirst("^https?://", "");
                    if (displayName.length() > 30) displayName = displayName.substring(0, 30) + "...";
                }
                if (bmIsHome) displayName = "🏠 " + displayName;
                nameText.setText(displayName);
                nameText.setTextColor(Color.WHITE);
                nameText.setTextSize(14);
                nameText.setTypeface(null, bm.label.isEmpty() ? Typeface.NORMAL : Typeface.BOLD);
                LinearLayout.LayoutParams nameLP = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                nameText.setLayoutParams(nameLP);
                // 点击名称跳转
                nameText.setOnClickListener(v -> {
                    loadUrl(bm.url);
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                });
                topRow.addView(nameText);

                // 编辑备注按钮
                TextView editBtn = new TextView(this);
                editBtn.setText("✏");
                editBtn.setTextColor(Color.parseColor("#AAAAAA"));
                editBtn.setTextSize(13);
                editBtn.setPadding(dp(6), dp(4), dp(6), dp(4));
                editBtn.setOnClickListener(v -> {
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    showEditLabelDialog(bm.url, bm.label);
                });
                topRow.addView(editBtn);

                // 设为主页
                TextView homeBtn = new TextView(this);
                homeBtn.setText("主页");
                homeBtn.setTextColor(bmIsHome ?
                    Color.parseColor("#4CAF50") : Color.parseColor("#666666"));
                homeBtn.setTextSize(11);
                homeBtn.setPadding(dp(6), dp(4), dp(6), dp(4));
                homeBtn.setOnClickListener(v -> {
                    setHomeUrl(bm.url);
                    Toast.makeText(this, "已设为主页", Toast.LENGTH_SHORT).show();
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                });
                topRow.addView(homeBtn);

                // 删除
                TextView delBtn = new TextView(this);
                delBtn.setText("✕");
                delBtn.setTextColor(Color.parseColor("#FF5555"));
                delBtn.setTextSize(14);
                delBtn.setPadding(dp(6), dp(4), dp(4), dp(4));
                delBtn.setOnClickListener(v -> {
                    removeBookmark(bm.url);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    showBookmarksDialog();
                });
                topRow.addView(delBtn);

                row.addView(topRow);

                // 下半部分：显示 URL（如果有备注的话，URL 作为副标题）
                if (!bm.label.isEmpty()) {
                    TextView urlSubText = new TextView(this);
                    String subUrl = bm.url.replaceFirst("^https?://", "");
                    if (subUrl.length() > 40) subUrl = subUrl.substring(0, 40) + "...";
                    urlSubText.setText(subUrl);
                    urlSubText.setTextColor(Color.parseColor("#666666"));
                    urlSubText.setTextSize(11);
                    urlSubText.setPadding(0, dp(1), 0, 0);
                    urlSubText.setOnClickListener(v -> {
                        loadUrl(bm.url);
                        if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    });
                    row.addView(urlSubText);
                }

                listContainer.addView(row);

                View sep = new View(this);
                sep.setBackgroundColor(Color.parseColor("#222222"));
                listContainer.addView(sep, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }

        scrollView.addView(listContainer);
        root.addView(scrollView);

        addDivider(root);

        // 手动添加书签
        TextView addLabel = new TextView(this);
        addLabel.setText("添加新收藏");
        addLabel.setTextColor(Color.parseColor("#888888"));
        addLabel.setTextSize(11);
        root.addView(addLabel);

        // 备注输入
        EditText addNote = new EditText(this);
        addNote.setHint("备注（可选，如「AI聊天」）");
        addNote.setTextColor(Color.WHITE);
        addNote.setHintTextColor(Color.parseColor("#555555"));
        addNote.setTextSize(13);
        addNote.setSingleLine(true);
        addNote.setBackgroundColor(Color.parseColor("#1A1A1A"));
        addNote.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams noteLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLP.topMargin = dp(4);
        root.addView(addNote, noteLP);

        // URL + 添加按钮
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addRowLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addRowLP.topMargin = dp(4);

        EditText addInput = new EditText(this);
        addInput.setHint("输入网址...");
        addInput.setTextColor(Color.WHITE);
        addInput.setHintTextColor(Color.parseColor("#555555"));
        addInput.setTextSize(13);
        addInput.setSingleLine(true);
        addInput.setBackgroundColor(Color.parseColor("#1A1A1A"));
        addInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams addLP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        addInput.setLayoutParams(addLP);
        addRow.addView(addInput);

        TextView addBtn = makeActionButton("添加");
        addBtn.setOnClickListener(v -> {
            String newUrl = addInput.getText().toString().trim();
            if (!newUrl.isEmpty()) {
                if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                    newUrl = "https://" + newUrl;
                }
                String note = addNote.getText().toString().trim();
                addBookmark(newUrl, note);
                Toast.makeText(this, "已添加收藏", Toast.LENGTH_SHORT).show();
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                showBookmarksDialog();
            }
        });
        addRow.addView(addBtn);
        root.addView(addRow, addRowLP);

        addDivider(root);

        // 自定义桌面图标
        TextView btnCustomIcon = makeActionButton("🎨 创建自定义桌面图标");
        btnCustomIcon.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            showCustomIconPicker();
        });
        LinearLayout.LayoutParams iconBtnLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconBtnLP.topMargin = dp(4);
        root.addView(btnCustomIcon, iconBtnLP);

        // 悬浮按钮设置
        TextView btnToggleSettings = makeActionButton("⚙ 悬浮按钮设置");
        btnToggleSettings.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            showToggleButtonSettings();
        });
        LinearLayout.LayoutParams tsBtnLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tsBtnLP.topMargin = dp(4);
        root.addView(btnToggleSettings, tsBtnLP);

        scroll.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setView(scroll)
                .create();

        dialogHolder[0] = dialog;

        // 夜间模式开关
        boolean darkMode = prefs.getBoolean("dark_mode", false);
        TextView btnDarkMode = makeActionButton(darkMode ? "🌙 网页深色模式：已开启" : "☀ 网页深色模式：已关闭");
        btnDarkMode.setTextColor(darkMode ? Color.parseColor("#FFC107") : Color.parseColor("#888888"));
        btnDarkMode.setOnClickListener(v -> {
            boolean newDark = !prefs.getBoolean("dark_mode", false);
            prefs.edit().putBoolean("dark_mode", newDark).apply();
            applyDarkMode(newDark);
            Toast.makeText(this, newDark ? "深色模式已开启" : "深色模式已关闭，刷新页面生效", Toast.LENGTH_SHORT).show();
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        LinearLayout.LayoutParams dmBtnLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dmBtnLP.topMargin = dp(4);
        root.addView(btnDarkMode, dmBtnLP);

        // 状态栏设置
        boolean showStatusBar = prefs.getBoolean("show_status_bar", false);
        TextView btnStatusBar = makeActionButton(
                showStatusBar ? "📶 显示状态栏：已开启" : "📶 显示状态栏：已关闭");
        btnStatusBar.setTextColor(showStatusBar ?
                Color.parseColor("#4CAF50") : Color.parseColor("#888888"));
        btnStatusBar.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("show_status_bar", false);
            prefs.edit().putBoolean("show_status_bar", newVal).apply();
            applyStatusBarVisibility(newVal);
            Toast.makeText(this,
                    newVal ? "状态栏已显示" : "状态栏已隐藏",
                    Toast.LENGTH_SHORT).show();
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        LinearLayout.LayoutParams sbBtnLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbBtnLP.topMargin = dp(4);
        root.addView(btnStatusBar, sbBtnLP);

        // 状态栏样式设置（仅在开启时显示）
        if (showStatusBar) {
            TextView btnStatusStyle = makeActionButton("🎨 状态栏样式设置");
            btnStatusStyle.setOnClickListener(v -> {
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                showStatusBarStyleDialog();
            });
            LinearLayout.LayoutParams ssBtnLP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ssBtnLP.topMargin = dp(4);
            root.addView(btnStatusStyle, ssBtnLP);
        }

        // 页面缩放
        TextView btnZoom = makeActionButton("🔍 页面缩放：" + prefs.getInt("page_zoom", 100) + "%");
        btnZoom.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            showZoomDialog();
        });
        LinearLayout.LayoutParams zoomBtnLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        zoomBtnLP.topMargin = dp(4);
        root.addView(btnZoom, zoomBtnLP);

        // 收藏/取消收藏
        btnSave.setOnClickListener(v -> {
            if (currentUrl == null) return;
            if (findBookmark(currentUrl) != null) {
                removeBookmark(currentUrl);
                Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                showBookmarksDialog();
            } else {
                // 弹出输入备注
                dialog.dismiss();
                showAddBookmarkWithLabel(currentUrl);
            }
        });

        btnSetHome.setOnClickListener(v -> {
            if (currentUrl == null) return;
            setHomeUrl(currentUrl);
            Toast.makeText(this, "已设为主页", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(0.7f);
        }

        dialog.show();
    }

    /** 收藏当前页时弹出备注输入 */
    private void showAddBookmarkWithLabel(String url) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        TextView title = new TextView(this);
        title.setText("☆ 收藏当前页");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView urlShow = new TextView(this);
        String shortUrl = url;
        if (shortUrl.length() > 50) shortUrl = shortUrl.substring(0, 50) + "...";
        urlShow.setText(shortUrl);
        urlShow.setTextColor(Color.parseColor("#888888"));
        urlShow.setTextSize(12);
        urlShow.setPadding(0, dp(4), 0, dp(12));
        root.addView(urlShow);

        TextView labelHint = new TextView(this);
        labelHint.setText("添加备注（方便区分）：");
        labelHint.setTextColor(Color.WHITE);
        labelHint.setTextSize(13);
        root.addView(labelHint);

        EditText labelInput = new EditText(this);
        labelInput.setHint("例如: AI聊天、游戏、工具...");
        labelInput.setTextColor(Color.WHITE);
        labelInput.setHintTextColor(Color.parseColor("#666666"));
        labelInput.setTextSize(14);
        labelInput.setSingleLine(true);
        labelInput.setBackgroundColor(Color.parseColor("#2A2A2A"));
        labelInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams inputLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLP.topMargin = dp(4);
        root.addView(labelInput, inputLP);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(root)
            .setPositiveButton("收藏", (d, w) -> {
                String label = labelInput.getText().toString().trim();
                addBookmark(url, label);
                Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("跳过备注", (d, w) -> {
                addBookmark(url, "");
                Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show();
            })
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    /** 编辑已有书签的备注 */
    private void showEditLabelDialog(String url, String currentLabelText) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        TextView title = new TextView(this);
        title.setText("✏ 编辑备注");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView urlShow = new TextView(this);
        String shortUrl = url.replaceFirst("^https?://", "");
        if (shortUrl.length() > 45) shortUrl = shortUrl.substring(0, 45) + "...";
        urlShow.setText(shortUrl);
        urlShow.setTextColor(Color.parseColor("#888888"));
        urlShow.setTextSize(12);
        urlShow.setPadding(0, dp(4), 0, dp(12));
        root.addView(urlShow);

        EditText labelInput = new EditText(this);
        labelInput.setText(currentLabelText);
        labelInput.setHint("输入备注...");
        labelInput.setTextColor(Color.WHITE);
        labelInput.setHintTextColor(Color.parseColor("#666666"));
        labelInput.setTextSize(14);
        labelInput.setSingleLine(true);
        labelInput.setBackgroundColor(Color.parseColor("#2A2A2A"));
        labelInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        labelInput.setSelectAllOnFocus(true);
        root.addView(labelInput);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(root)
            .setPositiveButton("保存", (d, w) -> {
                String newLabel = labelInput.getText().toString().trim();
                updateBookmarkLabel(url, newLabel);
                Toast.makeText(this, "备注已更新", Toast.LENGTH_SHORT).show();
                showBookmarksDialog();
            })
            .setNeutralButton("清除备注", (d, w) -> {
                updateBookmarkLabel(url, "");
                Toast.makeText(this, "备注已清除", Toast.LENGTH_SHORT).show();
                showBookmarksDialog();
            })
            .setNegativeButton("取消", null)
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    // ============ 悬浮按钮设置 ============

    private void applyToggleButtonSettings() {
        int size = prefs.getInt(KEY_BTN_SIZE, 20);
        float alpha = prefs.getFloat(KEY_BTN_ALPHA, 0.15f);

        android.widget.RelativeLayout.LayoutParams lp =
            (android.widget.RelativeLayout.LayoutParams) btnToggle.getLayoutParams();
        lp.width = dp(size);
        lp.height = dp(size);

        // 恢复保存的位置
        float savedX = prefs.getFloat(KEY_BTN_X, -1f);
        float savedY = prefs.getFloat(KEY_BTN_Y, -1f);
        if (savedX >= 0 && savedY >= 0) {
            // 用绝对定位：清除 alignParent 规则，用 margin 定位
            lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            // 延迟到布局完成后设置位置（需要知道父容器大小）
            btnToggle.post(() -> {
                View parent = (View) btnToggle.getParent();
                int pw = parent.getWidth();
                int ph = parent.getHeight();
                int bw = btnToggle.getWidth() > 0 ? btnToggle.getWidth() : dp(size);
                int bh = btnToggle.getHeight() > 0 ? btnToggle.getHeight() : dp(size);
                int x = (int) (savedX * pw);
                int y = (int) (savedY * ph);
                // 防止超出边界被挤扁
                x = Math.max(0, Math.min(x, pw - bw));
                y = Math.max(0, Math.min(y, ph - bh));
                lp.leftMargin = x;
                lp.topMargin = y;
                lp.rightMargin = 0;
                lp.bottomMargin = 0;
                lp.width = dp(size);
                lp.height = dp(size);
                btnToggle.setLayoutParams(lp);
            });
        }

        btnToggle.setAlpha(alpha);
        btnToggle.setPadding(dp(size / 5), dp(size / 5), dp(size / 5), dp(size / 5));
        btnToggle.setLayoutParams(lp);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupToggleDrag() {
        final float[] downX = {0}, downY = {0};
        final float[] startMarginLeft = {0}, startMarginTop = {0};
        final boolean[] isDragging = {false};
        final long[] downTime = {0};

        btnToggle.setOnTouchListener((v, event) -> {
            android.widget.RelativeLayout.LayoutParams lp =
                (android.widget.RelativeLayout.LayoutParams) v.getLayoutParams();

            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    // 获取按钮相对于父容器的实际位置
                    View parentDown = (View) v.getParent();
                    int[] btnLoc = new int[2];
                    int[] parentLoc = new int[2];
                    v.getLocationOnScreen(btnLoc);
                    parentDown.getLocationOnScreen(parentLoc);
                    startMarginLeft[0] = btnLoc[0] - parentLoc[0];
                    startMarginTop[0] = btnLoc[1] - parentLoc[1];
                    isDragging[0] = false;
                    downTime[0] = System.currentTimeMillis();
                    break;

                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    // 移动超过 10dp 才算拖动
                    if (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10)) {
                        isDragging[0] = true;
                        // 拖动时提高透明度方便看清位置
                        v.setAlpha(0.8f);

                        // 清除 alignParent 规则
                        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
                        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
                        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
                        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);

                        View parent = (View) v.getParent();
                        int newX = (int) (startMarginLeft[0] + dx);
                        int newY = (int) (startMarginTop[0] + dy);
                        newX = Math.max(0, Math.min(newX, parent.getWidth() - v.getWidth()));
                        newY = Math.max(0, Math.min(newY, parent.getHeight() - v.getHeight()));
                        lp.leftMargin = newX;
                        lp.topMargin = newY;
                        lp.rightMargin = 0;
                        lp.bottomMargin = 0;
                        v.setLayoutParams(lp);
                    }
                    break;

                case android.view.MotionEvent.ACTION_UP:
                    if (isDragging[0]) {
                        // 保存位置（百分比）
                        View parent = (View) v.getParent();
                        float xPct = (float) lp.leftMargin / parent.getWidth();
                        float yPct = (float) lp.topMargin / parent.getHeight();
                        prefs.edit()
                            .putFloat(KEY_BTN_X, xPct)
                            .putFloat(KEY_BTN_Y, yPct)
                            .apply();
                        // 恢复透明度
                        float savedAlpha = prefs.getFloat(KEY_BTN_ALPHA, 0.15f);
                        v.setAlpha(savedAlpha);
                        Toast.makeText(this, "位置已保存", Toast.LENGTH_SHORT).show();
                    } else {
                        // 短按 → 切换工具栏
                        v.performClick();
                    }
                    isDragging[0] = false;
                    break;
            }
            return true;
        });
    }

    private void showToggleButtonSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        TextView title = new TextView(this);
        title.setText("⚙ 悬浮按钮设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("💡 长按按钮可直接拖动到任意位置");
        tip.setTextColor(Color.parseColor("#888888"));
        tip.setTextSize(11);
        tip.setPadding(0, dp(4), 0, dp(12));
        root.addView(tip);

        // 大小设置
        int currentSize = prefs.getInt(KEY_BTN_SIZE, 20);
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("大小：" + currentSize + "dp");
        sizeLabel.setTextColor(Color.WHITE);
        sizeLabel.setTextSize(13);
        root.addView(sizeLabel);

        android.widget.SeekBar sizeBar = new android.widget.SeekBar(this);
        sizeBar.setMax(40); // 10 ~ 50
        sizeBar.setProgress(currentSize - 10);
        sizeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int size = progress + 10;
                sizeLabel.setText("大小：" + size + "dp");
                int sizePx = dp(size);

                android.widget.RelativeLayout.LayoutParams lp =
                        (android.widget.RelativeLayout.LayoutParams) btnToggle.getLayoutParams();

                View parent = (View) btnToggle.getParent();
                if (parent != null && parent.getWidth() > 0) {
                    // 获取当前实际位置
                    int[] btnLoc = new int[2];
                    int[] parentLoc = new int[2];
                    btnToggle.getLocationOnScreen(btnLoc);
                    parent.getLocationOnScreen(parentLoc);
                    int curX = btnLoc[0] - parentLoc[0];
                    int curY = btnLoc[1] - parentLoc[1];

                    // 如果右边超出，向左扩展
                    if (curX + sizePx > parent.getWidth()) {
                        curX = parent.getWidth() - sizePx;
                    }
                    // 如果下边超出，向上扩展
                    if (curY + sizePx > parent.getHeight()) {
                        curY = parent.getHeight() - sizePx;
                    }
                    curX = Math.max(0, curX);
                    curY = Math.max(0, curY);

                    // 切到绝对定位
                    lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
                    lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
                    lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
                    lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
                    lp.leftMargin = curX;
                    lp.topMargin = curY;
                    lp.rightMargin = 0;
                    lp.bottomMargin = 0;

                    // 保存新位置
                    float xPct = (float) curX / parent.getWidth();
                    float yPct = (float) curY / parent.getHeight();
                    prefs.edit().putFloat(KEY_BTN_X, xPct).putFloat(KEY_BTN_Y, yPct).apply();
                }

                lp.width = sizePx;
                lp.height = sizePx;
                btnToggle.setPadding(dp(size / 5), dp(size / 5), dp(size / 5), dp(size / 5));
                btnToggle.setLayoutParams(lp);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams sizeLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sizeLP.bottomMargin = dp(12);
        root.addView(sizeBar, sizeLP);

        // 透明度设置
        float currentAlpha = prefs.getFloat(KEY_BTN_ALPHA, 0.15f);
        int alphaPercent = (int) (currentAlpha * 100);
        TextView alphaLabel = new TextView(this);
        alphaLabel.setText("透明度：" + alphaPercent + "%");
        alphaLabel.setTextColor(Color.WHITE);
        alphaLabel.setTextSize(13);
        root.addView(alphaLabel);

        android.widget.SeekBar alphaBar = new android.widget.SeekBar(this);
        alphaBar.setMax(100); // 0% ~ 100%
        alphaBar.setProgress(alphaPercent);
        alphaBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                alphaLabel.setText("透明度：" + progress + "%");
                btnToggle.setAlpha(progress / 100f);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams alphaLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alphaLP.bottomMargin = dp(12);
        root.addView(alphaBar, alphaLP);

        // 坐标微调
        addDivider(root);

        TextView posLabel = new TextView(this);
        posLabel.setText("位置微调");
        posLabel.setTextColor(Color.WHITE);
        posLabel.setTextSize(13);
        root.addView(posLabel);

        // 坐标显示
        TextView coordText = new TextView(this);
        coordText.setTextColor(Color.parseColor("#AAAAAA"));
        coordText.setTextSize(11);
        btnToggle.post(() -> {
            int[] btnLoc = new int[2];
            int[] parentLoc = new int[2];
            btnToggle.getLocationOnScreen(btnLoc);
            ((View) btnToggle.getParent()).getLocationOnScreen(parentLoc);
            coordText.setText("X=" + (btnLoc[0] - parentLoc[0]) + "  Y=" + (btnLoc[1] - parentLoc[1]));
        });
        root.addView(coordText);

        // 方向按钮
        int step = dp(5);

        LinearLayout arrowRow1 = new LinearLayout(this);
        arrowRow1.setGravity(Gravity.CENTER);
        TextView btnUp = makeActionButton("  ▲  ");
        btnUp.setOnClickListener(v -> moveToggle(0, -step, coordText));
        arrowRow1.addView(btnUp);
        LinearLayout.LayoutParams ar1LP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ar1LP.topMargin = dp(8);
        root.addView(arrowRow1, ar1LP);

        LinearLayout arrowRow2 = new LinearLayout(this);
        arrowRow2.setGravity(Gravity.CENTER);
        TextView btnLeft = makeActionButton("  ◀  ");
        btnLeft.setOnClickListener(v -> moveToggle(-step, 0, coordText));
        arrowRow2.addView(btnLeft);
        addSpacer(arrowRow2, dp(16));
        TextView btnDown = makeActionButton("  ▼  ");
        btnDown.setOnClickListener(v -> moveToggle(0, step, coordText));
        arrowRow2.addView(btnDown);
        addSpacer(arrowRow2, dp(16));
        TextView btnRight = makeActionButton("  ▶  ");
        btnRight.setOnClickListener(v -> moveToggle(step, 0, coordText));
        arrowRow2.addView(btnRight);
        LinearLayout.LayoutParams ar2LP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ar2LP.topMargin = dp(4);
        ar2LP.bottomMargin = dp(8);
        root.addView(arrowRow2, ar2LP);

        // 重置位置按钮
        TextView btnReset = makeActionButton("↩ 重置到右下角默认位置");
        btnReset.setOnClickListener(v -> {
            prefs.edit()
                .remove(KEY_BTN_X).remove(KEY_BTN_Y)
                .remove(KEY_BTN_SIZE).remove(KEY_BTN_ALPHA)
                .apply();
            // 恢复默认
            android.widget.RelativeLayout.LayoutParams lp =
                (android.widget.RelativeLayout.LayoutParams) btnToggle.getLayoutParams();
            lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
            lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
            lp.width = dp(20);
            lp.height = dp(20);
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = dp(12);
            lp.bottomMargin = dp(16);
            btnToggle.setPadding(dp(4), dp(4), dp(4), dp(4));
            btnToggle.setAlpha(0.15f);
            btnToggle.setLayoutParams(lp);
            Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(btnReset, resetLP);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(root)
            .setPositiveButton("保存", (d, w) -> {
                int newSize = sizeBar.getProgress() + 10;
                float newAlpha = alphaBar.getProgress() / 100f;
                prefs.edit()
                    .putInt(KEY_BTN_SIZE, newSize)
                    .putFloat(KEY_BTN_ALPHA, newAlpha)
                    .apply();
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", (d, w) -> {
                // 取消时恢复之前的设置
                applyToggleButtonSettings();
            })
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }
    private void moveToggle(int dx, int dy, TextView coordText) {
        android.widget.RelativeLayout.LayoutParams lp =
                (android.widget.RelativeLayout.LayoutParams) btnToggle.getLayoutParams();

        // 确保用绝对定位
        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);

        // 如果还没设过 leftMargin，先获取当前实际位置
        View parent = (View) btnToggle.getParent();
        int[] btnLoc = new int[2];
        int[] parentLoc = new int[2];
        btnToggle.getLocationOnScreen(btnLoc);
        parent.getLocationOnScreen(parentLoc);
        int curX = btnLoc[0] - parentLoc[0];
        int curY = btnLoc[1] - parentLoc[1];

        int newX = Math.max(0, Math.min(curX + dx, parent.getWidth() - btnToggle.getWidth()));
        int newY = Math.max(0, Math.min(curY + dy, parent.getHeight() - btnToggle.getHeight()));

        lp.leftMargin = newX;
        lp.topMargin = newY;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        btnToggle.setLayoutParams(lp);

        // 更新坐标显示
        coordText.setText("X=" + newX + "  Y=" + newY);

        // 保存位置
        float xPct = (float) newX / parent.getWidth();
        float yPct = (float) newY / parent.getHeight();
        prefs.edit().putFloat(KEY_BTN_X, xPct).putFloat(KEY_BTN_Y, yPct).apply();
    }
    private void applyDarkMode(boolean enable) {
        WebSettings settings = webView.getSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(enable);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(enable ?
                    WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        }
    }
    private void showZoomDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        int currentZoom = prefs.getInt("page_zoom", 100);

        TextView title = new TextView(this);
        title.setText("🔍 页面缩放");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView zoomLabel = new TextView(this);
        zoomLabel.setText("缩放：" + currentZoom + "%");
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(13);
        zoomLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(zoomLabel);

        EditText zoomInput = new EditText(this);
        zoomInput.setText(String.valueOf(currentZoom));
        zoomInput.setTextColor(Color.WHITE);
        zoomInput.setTextSize(14);
        zoomInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        zoomInput.setSingleLine(true);
        zoomInput.setBackgroundColor(Color.parseColor("#2A2A2A"));
        zoomInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        zoomInput.setSelectAllOnFocus(true);
        root.addView(zoomInput);

        android.widget.SeekBar zoomBar = new android.widget.SeekBar(this);
        zoomBar.setMax(200);
        zoomBar.setProgress(currentZoom - 50);
        zoomBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int zoom = progress + 50;
                zoomLabel.setText("缩放：" + zoom + "%");
                zoomInput.setText(String.valueOf(zoom));
                webView.getSettings().setTextZoom(zoom);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                int zoom = seekBar.getProgress() + 50;
                prefs.edit().putInt("page_zoom", zoom).apply();
            }
        });
        LinearLayout.LayoutParams barLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLP.topMargin = dp(8);
        root.addView(zoomBar, barLP);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLP.topMargin = dp(12);

        TextView btnApply = makeActionButton("输入后应用");
        btnApply.setOnClickListener(v -> {
            try {
                int z = Integer.parseInt(zoomInput.getText().toString().trim());
                z = Math.max(50, Math.min(250, z));
                zoomInput.setText(String.valueOf(z));
                zoomBar.setProgress(z - 50);
                zoomLabel.setText("缩放：" + z + "%");
                webView.getSettings().setTextZoom(z);
                prefs.edit().putInt("page_zoom", z).apply();
            } catch (NumberFormatException ignored) {
                Toast.makeText(this, "请输入 50-250 之间的数字", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnApply);

        addSpacer(btnRow, dp(8));

        TextView btnReset = makeActionButton("重置 100%");
        btnReset.setOnClickListener(v -> {
            zoomBar.setProgress(50);
            zoomInput.setText("100");
            zoomLabel.setText("缩放：100%");
            webView.getSettings().setTextZoom(100);
            prefs.edit().putInt("page_zoom", 100).apply();
        });
        btnRow.addView(btnReset);

        root.addView(btnRow, rowLP);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private void applyStatusBarVisibility(boolean show) {
        Window window = getWindow();

        // 读取颜色设置
        String colorStr = prefs.getString("status_bar_color", "#00000000");
        int statusColor;
        try {
            statusColor = Color.parseColor(colorStr);
        } catch (Exception e) {
            statusColor = Color.TRANSPARENT;
        }

        boolean lightIcons = prefs.getBoolean("status_bar_light_icons", false);

        window.setStatusBarColor(show ? statusColor : Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                if (show) {
                    controller.show(WindowInsets.Type.statusBars());
                    if (lightIcons) {
                        // 浅色图标（深色背景用）
                        controller.setSystemBarsAppearance(0,
                                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    } else {
                        // 深色图标（浅色背景用）
                        controller.setSystemBarsAppearance(
                                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    }
                } else {
                    controller.hide(WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } else {
            if (show) {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                if (!lightIcons) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                window.getDecorView().setSystemUiVisibility(flags);
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }

        if (toolbar != null) {
            int topPadding = show ? getStatusBarHeight() + dp(8) : dp(48);
            toolbar.setPadding(dp(8), topPadding, dp(8), dp(4));
        }
    }

    private int getStatusBarHeight() {
        int height = dp(48); // 默认值
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            height = getResources().getDimensionPixelSize(resourceId);
        }
        return height;
    }
    // ============ 自定义桌面图标 ============

    private void showCustomIconPicker() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        TextView title = new TextView(this);
        title.setText("🎨 创建自定义桌面快捷方式");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("选择一张图片作为图标，将在桌面创建快捷方式。\n点击快捷方式即可打开本应用。");
        desc.setTextColor(Color.parseColor("#AAAAAA"));
        desc.setTextSize(12);
        desc.setPadding(0, dp(8), 0, dp(16));
        root.addView(desc);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("快捷方式名称：");
        nameLabel.setTextColor(Color.WHITE);
        nameLabel.setTextSize(13);
        root.addView(nameLabel);

        EditText nameInput = new EditText(this);
        nameInput.setText("全屏浏览器");
        nameInput.setTextColor(Color.WHITE);
        nameInput.setTextSize(14);
        nameInput.setSingleLine(true);
        nameInput.setBackgroundColor(Color.parseColor("#2A2A2A"));
        nameInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        nameInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams nameLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLP.topMargin = dp(4);
        nameLP.bottomMargin = dp(16);
        root.addView(nameInput, nameLP);

        AlertDialog[] dialogHolder = new AlertDialog[1];

        TextView btnPick = makeActionButton("📷 从相册选择图片");
        btnPick.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pickLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(btnPick, pickLP);

        btnPick.setOnClickListener(v -> {
            prefs.edit().putString("pending_shortcut_name",
                nameInput.getText().toString().trim()).apply();
            Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            try {
                startActivityForResult(intent, ICON_PICKER_REQUEST);
            } catch (Exception e) {
                // fallback
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("image/*");
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(
                    Intent.createChooser(fallback, "选择图片"), ICON_PICKER_REQUEST);
            }
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(root)
            .create();
        dialogHolder[0] = dialog;

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void createShortcutWithIcon(Bitmap iconBitmap, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                int size = 192;
                Bitmap scaled = Bitmap.createScaledBitmap(iconBitmap, size, size, true);

                Intent launchIntent = new Intent(this, MainActivity.class);
                launchIntent.setAction(Intent.ACTION_MAIN);

                ShortcutInfo shortcut = new ShortcutInfo.Builder(this,
                        "custom_" + System.currentTimeMillis())
                    .setShortLabel(name.isEmpty() ? "全屏浏览器" : name)
                    .setIcon(Icon.createWithBitmap(scaled))
                    .setIntent(launchIntent)
                    .build();

                shortcutManager.requestPinShortcut(shortcut, null);
                Toast.makeText(this, "请确认添加桌面快捷方式", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "你的设备不支持添加快捷方式", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "需要 Android 8.0 以上",
                Toast.LENGTH_LONG).show();
        }
    }

    // ============ UI Helpers ============

    private TextView makeActionButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setBackgroundColor(Color.parseColor("#2A2A2A"));
        return btn;
    }

    private void addSpacer(LinearLayout parent, int width) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        parent.addView(spacer);
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(8);
        parent.addView(divider, lp);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ============ WebView Setup ============

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(prefs.getInt("page_zoom", 100));
        // ★ 根据设置决定深色模式
        applyDarkMode(prefs.getBoolean("dark_mode", false));
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new BlobDownloadInterface(), "AndroidBlobDownloader");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url.startsWith("blob:")) {
                    handleBlobDownload(url, mimeType, contentDisposition);
                } else if (url.startsWith("data:")) {
                    handleDataDownload(url);
                } else {
                    handleHttpDownload(url, userAgent, contentDisposition, mimeType);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                urlBar.setText(url);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程崩了，重新加载
                String url = view.getUrl();
                if (url != null) {
                    view.loadUrl(url);
                }
                Toast.makeText(MainActivity.this, "页面已自动恢复",
                        Toast.LENGTH_SHORT).show();
                return true; // true = 我们自己处理，不要崩 app
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                prefs.edit().putString(KEY_LAST_URL, url).apply();

                view.evaluateJavascript(
                    "(function(){" +
                    "var m=document.querySelector('meta[name=viewport]');" +
                    "if(m){if(m.content.indexOf('viewport-fit=cover')===-1)" +
                    "m.content+=', viewport-fit=cover';}" +
                    "else{m=document.createElement('meta');m.name='viewport';" +
                    "m.content='width=device-width,initial-scale=1,viewport-fit=cover';" +
                    "document.head.appendChild(m);}" +
                    "if(!window._blobHooked){" +
                    "  window._blobHooked=true;" +
                    "  window._blobStore={};" +
                    "  var origCreate=URL.createObjectURL;" +
                    "  URL.createObjectURL=function(blob){" +
                    "    var url=origCreate.call(URL,blob);" +
                    "    if(blob instanceof Blob) window._blobStore[url]=blob;" +
                    "    return url;" +
                    "  };" +
                    "  var origRevoke=URL.revokeObjectURL;" +
                    "  URL.revokeObjectURL=function(url){" +
                    "    setTimeout(function(){" +
                    "      delete window._blobStore[url];" +
                    "      origRevoke.call(URL,url);" +
                    "    },5000);" +
                    "  };" +
                    "}" +
                    // 持久化存储 API 支持（放在 blob hook 外面）
                    "if(navigator.storage&&!navigator.storage._patched){" +
                    "  navigator.storage._patched=true;" +
                    "  navigator.storage.persist=function(){return Promise.resolve(true);};" +
                    "  navigator.storage.persisted=function(){return Promise.resolve(true);};" +
                    "}" +
                    "})();", null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // ★ 允许网页打开新窗口（有些网站用新窗口触发下载）
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                WebView tempView = new WebView(MainActivity.this);
                tempView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        webView.loadUrl(url);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tempView);
                resultMsg.sendToTarget();
                return true;
            }
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                // 把网页要的所有类型都转成 MIME type
                String[] types = fileChooserParams.getAcceptTypes();
                java.util.List<String> mimeList = new java.util.ArrayList<>();
                boolean wantsImage = false;
                if (types != null) {
                    for (String t : types) {
                        if (t == null || t.isEmpty()) continue;
                        String[] parts = t.split(",");
                        for (String p : parts) {
                            p = p.trim();
                            if (p.isEmpty()) continue;
                            if (p.contains("/")) {
                                mimeList.add(p);
                                if (p.startsWith("image")) wantsImage = true;
                            } else if (p.startsWith(".")) {
                                String ext = p.toLowerCase();
                                if (ext.equals(".json")) mimeList.add("application/json");
                                else if (ext.equals(".txt")) mimeList.add("text/plain");
                                else if (ext.equals(".csv")) mimeList.add("text/csv");
                                else if (ext.equals(".xml")) mimeList.add("text/xml");
                                else if (ext.equals(".html") || ext.equals(".htm")) mimeList.add("text/html");
                                else if (ext.equals(".pdf")) mimeList.add("application/pdf");
                                else if (ext.equals(".zip")) mimeList.add("application/zip");
                                else if (ext.equals(".doc")) mimeList.add("application/msword");
                                else if (ext.equals(".docx")) mimeList.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                                else if (ext.equals(".xls")) mimeList.add("application/vnd.ms-excel");
                                else if (ext.equals(".xlsx")) mimeList.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                                else if (ext.equals(".png")) { mimeList.add("image/png"); wantsImage = true; }
                                else if (ext.equals(".jpg") || ext.equals(".jpeg")) { mimeList.add("image/jpeg"); wantsImage = true; }
                                else if (ext.equals(".gif")) { mimeList.add("image/gif"); wantsImage = true; }
                                else if (ext.equals(".webp")) { mimeList.add("image/webp"); wantsImage = true; }
                                else if (ext.equals(".mp4")) mimeList.add("video/mp4");
                                else if (ext.equals(".mp3")) mimeList.add("audio/mpeg");
                                else mimeList.add("*/*");
                            }
                        }
                    }
                }

                // 只要图片类型 → 走相册
                if (mimeList.size() > 0 && wantsImage && !mimeList.stream().anyMatch(m -> !m.startsWith("image"))) {
                    Intent intent = new Intent(Intent.ACTION_PICK,
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    intent.setType(mimeList.size() == 1 ? mimeList.get(0) : "image/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(
                                Intent.createChooser(intent, "选择图片"), FILE_CHOOSER_REQUEST);
                        return true;
                    } catch (Exception ignored) {}
                }

                // 非纯图片 → 走文件管理器
                Intent intent;
                if (mimeList.isEmpty() || mimeList.contains("*/*")) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                } else if (mimeList.size() == 1) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(mimeList.get(0));
                } else {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,
                            mimeList.toArray(new String[0]));
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST);
                } catch (Exception e1) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("*/*");
                        fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(
                                Intent.createChooser(fallback, "选择文件"), FILE_CHOOSER_REQUEST);
                    } catch (Exception e2) {
                        if (fileUploadCallback != null) {
                            fileUploadCallback.onReceiveValue(null);
                            fileUploadCallback = null;
                        }
                        Toast.makeText(MainActivity.this, "无法打开文件选择器",
                                Toast.LENGTH_LONG).show();
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    // 检查需要哪些 Android 权限
                    String[] resources = request.getResources();
                    java.util.List<String> needed = new java.util.ArrayList<>();
                    for (String r : resources) {
                        if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            if (checkSelfPermission(Manifest.permission.CAMERA)
                                    != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.CAMERA);
                            }
                        }
                        if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.RECORD_AUDIO);
                            }
                        }
                    }

                    if (needed.isEmpty()) {
                        request.grant(resources);
                    } else {
                        // 保存请求，等权限回调后再授权
                        pendingPermissionRequest = request;
                        requestPermissions(
                                needed.toArray(new String[0]), 2001);
                    }
                });
            }
        });
    }

    // ============ Activity Results ============

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileUploadCallback == null) return;

            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }

            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;

        } else if (requestCode == ICON_PICKER_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    InputStream is = getContentResolver().openInputStream(data.getData());
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (is != null) is.close();

                    if (bitmap != null) {
                        String name = prefs.getString("pending_shortcut_name", "全屏浏览器");
                        createShortcutWithIcon(bitmap, name);
                    } else {
                        Toast.makeText(this, "无法读取图片", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "图片读取失败: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2001 && pendingPermissionRequest != null) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
                Toast.makeText(this, "需要相机/麦克风权限才能使用此功能", Toast.LENGTH_LONG).show();
            }
            pendingPermissionRequest = null;
        }
    }

    // ============ Download Handlers ============

    private void handleHttpDownload(String url, String userAgent,
                                    String contentDisposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setTitle(filename);
            request.setDescription("下载中...");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(this, "下载: " + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleBlobDownload(String blobUrl, String mimeType, String contentDisposition) {
        String filename = null;
        if (contentDisposition != null && contentDisposition.contains("filename")) {
            try {
                if (contentDisposition.contains("filename*=")) {
                    filename = contentDisposition.split("filename\\*=")[1]
                        .replaceAll("(?i)utf-8''", "").split(";")[0].trim();
                } else {
                    filename = contentDisposition.split("filename=")[1]
                        .replace("\"", "").split(";")[0].trim();
                }
            } catch (Exception ignored) {}
        }
        if (filename == null || filename.isEmpty()) {
            String ext = guessExtension(mimeType);
            filename = "download_" + System.currentTimeMillis() + ext;
        }

        final String finalFilename = filename;
        String escapedUrl = blobUrl.replace("'", "\\'");

        String js = "(async function() {" +
            "try {" +
            "  var blob;" +
            "  if(window._blobStore && window._blobStore['" + escapedUrl + "']){" +
            "    blob = window._blobStore['" + escapedUrl + "'];" +
            "  } else {" +
            "    let response = await fetch('" + escapedUrl + "');" +
            "    blob = await response.blob();" +
            "  }" +
            "  let size = blob.size;" +
            "  let chunkSize = 384 * 1024;" +
            "  AndroidBlobDownloader.onBlobStart('" +
                    finalFilename.replace("'", "\\'") + "', size);" +
            "  for (let offset = 0; offset < size; offset += chunkSize) {" +
            "    let slice = blob.slice(offset, Math.min(offset + chunkSize, size));" +
            "    let base64 = await new Promise((resolve, reject) => {" +
            "      let r = new FileReader();" +
            "      r.onloadend = () => resolve(r.result.split(',')[1]);" +
            "      r.onerror = () => reject(r.error);" +
            "      r.readAsDataURL(slice);" +
            "    });" +
            "    AndroidBlobDownloader.onBlobChunk(base64);" +
            "  }" +
            "  AndroidBlobDownloader.onBlobEnd();" +
            "} catch(e) {" +
            "  AndroidBlobDownloader.onBlobError(e.toString());" +
            "}" +
            "})();";

        webView.evaluateJavascript(js, null);
        Toast.makeText(this, "正在读取: " + finalFilename, Toast.LENGTH_SHORT).show();
    }

    private void handleDataDownload(String dataUrl) {
        try {
            String[] parts = dataUrl.split(",", 2);
            if (parts.length < 2) return;
            String meta = parts[0];
            String base64Data = parts[1];
            String mimeType = meta.replace("data:", "").replace(";base64", "");
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            String ext = guessExtension(mimeType);
            saveBytes(bytes, "download_" + System.currentTimeMillis() + ext);
        } catch (Exception e) {
            Toast.makeText(this, "data URL 解析失败: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void saveBytes(byte[] bytes, String filename) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 用 MediaStore
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                    }
                }
            } else {
                // Android 9 及以下用老方法
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
            }
            Toast.makeText(this, "已保存到 Downloads: " + filename, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String guessExtension(String mimeType) {
        if (mimeType == null) return ".bin";
        if (mimeType.contains("json")) return ".json";
        if (mimeType.contains("csv")) return ".csv";
        if (mimeType.contains("text/plain")) return ".txt";
        if (mimeType.contains("html")) return ".html";
        if (mimeType.contains("xml")) return ".xml";
        if (mimeType.contains("zip")) return ".zip";
        if (mimeType.contains("pdf")) return ".pdf";
        if (mimeType.contains("png")) return ".png";
        if (mimeType.contains("jpeg") || mimeType.contains("jpg")) return ".jpg";
        if (mimeType.contains("gif")) return ".gif";
        if (mimeType.contains("webp")) return ".webp";
        if (mimeType.contains("mp4")) return ".mp4";
        if (mimeType.contains("mp3") || mimeType.contains("mpeg")) return ".mp3";
        return ".bin";
    }

    // ============ JS Bridge ============

    class BlobDownloadInterface {
        private OutputStream outputStream;
        private File outputFile;
        private String currentFilename;

        @JavascriptInterface
        public void onBlobStart(String filename, long totalSize) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        outputStream = getContentResolver().openOutputStream(uri);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    outputFile = new File(dir, filename);
                    outputStream = new FileOutputStream(outputFile);
                }
                currentFilename = filename;
            } catch (IOException e) {
                Log.e(TAG, "Failed to create file: " + e.getMessage());
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "创建文件失败: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void onBlobChunk(String base64Chunk) {
            try {
                if (outputStream != null) {
                    byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                    outputStream.write(bytes);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to write chunk: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void onBlobEnd() {
            try {
                if (outputStream != null) {
                    outputStream.flush();
                    outputStream.close();
                    outputStream = null;
                }
                final String name = currentFilename;
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "已保存到 Downloads: " + name,
                        Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                Log.e(TAG, "Failed to close file: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void onBlobError(String error) {
            try {
                if (outputStream != null) { outputStream.close(); outputStream = null; }
                if (outputFile != null && outputFile.exists()) outputFile.delete();
            } catch (IOException ignored) {}
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, "blob 下载失败: " + error,
                    Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void onBlobReady(String dataUrl, String mimeType) {
            runOnUiThread(() -> {
                try {
                    String[] parts = dataUrl.split(",", 2);
                    if (parts.length < 2) return;
                    String meta = parts[0];
                    String base64Data = parts[1];
                    String actualMime = mimeType;
                    if (meta.contains(":") && meta.contains(";")) {
                        actualMime = meta.substring(meta.indexOf(":") + 1, meta.indexOf(";"));
                    }
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    String ext = guessExtension(actualMime);
                    saveBytes(bytes, "download_" + System.currentTimeMillis() + ext);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "blob 保存失败: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // ============ Navigation & Fullscreen ============

    private void loadUrl(String url) {
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("file://")) {
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                url = "https://www.google.com/search?q=" + Uri.encode(url);
            }
        }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            long now = System.currentTimeMillis();
            if (now - lastBackPressTime < BACK_PRESS_INTERVAL) {
                super.onBackPressed();
            } else {
                lastBackPressTime = now;
                Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            boolean showStatusBar = prefs != null && prefs.getBoolean("show_status_bar", false);
            if (showStatusBar) {
                applyStatusBarVisibility(true);
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            }
        }
    }
    private void showStatusBarStyleDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));

        TextView title = new TextView(this);
        title.setText("🎨 状态栏样式");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // 图标颜色切换
        boolean lightIcons = prefs.getBoolean("status_bar_light_icons", false);
        TextView iconLabel = new TextView(this);
        iconLabel.setText("状态栏图标颜色");
        iconLabel.setTextColor(Color.WHITE);
        iconLabel.setTextSize(13);
        iconLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(iconLabel);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnDarkIcons = makeActionButton("● 深色图标");
        btnDarkIcons.setTextColor(!lightIcons ? Color.parseColor("#4CAF50") : Color.WHITE);
        TextView btnLightIcons = makeActionButton("○ 浅色图标");
        btnLightIcons.setTextColor(lightIcons ? Color.parseColor("#4CAF50") : Color.WHITE);

        final boolean[] currentLight = {lightIcons};
        btnDarkIcons.setOnClickListener(v -> {
            currentLight[0] = false;
            btnDarkIcons.setTextColor(Color.parseColor("#4CAF50"));
            btnLightIcons.setTextColor(Color.WHITE);
            prefs.edit().putBoolean("status_bar_light_icons", false).apply();
            applyStatusBarVisibility(true);
        });
        btnLightIcons.setOnClickListener(v -> {
            currentLight[0] = true;
            btnLightIcons.setTextColor(Color.parseColor("#4CAF50"));
            btnDarkIcons.setTextColor(Color.WHITE);
            prefs.edit().putBoolean("status_bar_light_icons", true).apply();
            applyStatusBarVisibility(true);
        });

        iconRow.addView(btnDarkIcons);
        addSpacer(iconRow, dp(8));
        iconRow.addView(btnLightIcons);
        root.addView(iconRow);

        // 背景颜色
        addDivider(root);

        TextView bgLabel = new TextView(this);
        bgLabel.setText("状态栏背景");
        bgLabel.setTextColor(Color.WHITE);
        bgLabel.setTextSize(13);
        root.addView(bgLabel);

        // 预设颜色
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crLP.topMargin = dp(8);

        String[][] presets = {
                {"透明", "#00000000"},
                {"黑色", "#FF000000"},
                {"白色", "#FFFFFFFF"},
                {"深灰", "#FF333333"},
                {"浅灰", "#FFE0E0E0"},
        };

        for (String[] preset : presets) {
            TextView btn = new TextView(this);
            btn.setText(preset[0]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(11);
            btn.setPadding(dp(10), dp(6), dp(10), dp(6));
            // 预览色块
            if (preset[1].equals("#00000000")) {
                btn.setBackgroundColor(Color.parseColor("#2A2A2A"));
            } else {
                try {
                    btn.setBackgroundColor(Color.parseColor(preset[1]));
                    // 浅色背景用深色字
                    int c = Color.parseColor(preset[1]);
                    if (Color.red(c) + Color.green(c) + Color.blue(c) > 400) {
                        btn.setTextColor(Color.BLACK);
                    }
                } catch (Exception ignored) {}
            }
            final String colorVal = preset[1];
            btn.setOnClickListener(v -> {
                prefs.edit().putString("status_bar_color", colorVal).apply();
                applyStatusBarVisibility(true);
                Toast.makeText(this, preset[0], Toast.LENGTH_SHORT).show();
            });
            colorRow.addView(btn);
            addSpacer(colorRow, dp(4));
        }

        root.addView(colorRow, crLP);

        // 自定义 hex 输入
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLP.topMargin = dp(8);

        TextView hashTag = new TextView(this);
        hashTag.setText("#");
        hashTag.setTextColor(Color.WHITE);
        hashTag.setTextSize(14);
        hexRow.addView(hashTag);

        EditText hexInput = new EditText(this);
        String savedColor = prefs.getString("status_bar_color", "#00000000");
        hexInput.setText(savedColor.replace("#", ""));
        hexInput.setTextColor(Color.WHITE);
        hexInput.setTextSize(13);
        hexInput.setSingleLine(true);
        hexInput.setBackgroundColor(Color.parseColor("#2A2A2A"));
        hexInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        hexInput.setHint("RRGGBB 或 AARRGGBB");
        hexInput.setHintTextColor(Color.parseColor("#555555"));
        LinearLayout.LayoutParams hexLP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hexInput.setLayoutParams(hexLP);
        hexRow.addView(hexInput);

        addSpacer(hexRow, dp(8));

        TextView btnApplyHex = makeActionButton("应用");
        btnApplyHex.setOnClickListener(v -> {
            String hex = hexInput.getText().toString().trim();
            try {
                Color.parseColor("#" + hex);
                prefs.edit().putString("status_bar_color", "#" + hex).apply();
                applyStatusBarVisibility(true);
                Toast.makeText(this, "颜色已应用", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "无效的颜色值", Toast.LENGTH_SHORT).show();
            }
        });
        hexRow.addView(btnApplyHex);

        root.addView(hexRow, hrLP);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }
}
