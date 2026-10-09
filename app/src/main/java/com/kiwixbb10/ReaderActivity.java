package com.kiwixbb10;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Full Kiwix reader screen with:
 * - Local ZIM HTTP server
 * - Search bar
 * - Random article generator
 * - Night/Dark mode injection
 * - Font zoom cycling (80%, 100%, 125%, 150%)
 * - Bookmarks management (Save & List)
 * - Full BlackBerry physical keyboard shortcuts
 */
public class ReaderActivity extends Activity {
    private static final String TAG = "ReaderActivity";
    private static final String PREFS_NAME = "KiwixBookmarks";
    private static final String PREF_BOOKMARKS_KEY = "bookmarks_list";

    private WebView     mWebView;
    private EditText    mEditSearch;
    private Button      mBtnBackLibrary;
    private Button      mBtnHome;
    private Button      mBtnRandom;
    private Button      mBtnNight;
    private Button      mBtnZoom;
    private Button      mBtnBookmark;
    private Button      mBtnBookmarksList;
    private Button      mBtnShare;
    private ProgressBar mProgress;

    private ZimReader     mReader;
    private ZimHttpServer mServer;
    private String        mZimPath;

    private boolean mIsNightMode = false;
    private int[]   mZoomLevels  = {80, 100, 125, 150};
    private int     mZoomIndex   = 1; // default 100%

    private String mCurrentUrl   = "";
    private String mCurrentTitle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        mWebView          = (WebView)     findViewById(R.id.webview);
        mEditSearch       = (EditText)    findViewById(R.id.edit_search);
        mBtnBackLibrary   = (Button)      findViewById(R.id.btn_back_library);
        mBtnHome          = (Button)      findViewById(R.id.btn_home);
        mBtnRandom        = (Button)      findViewById(R.id.btn_random);
        mBtnNight         = (Button)      findViewById(R.id.btn_night);
        mBtnZoom          = (Button)      findViewById(R.id.btn_zoom);
        mBtnBookmark      = (Button)      findViewById(R.id.btn_bookmark);
        mBtnBookmarksList = (Button)      findViewById(R.id.btn_bookmarks_list);
        mBtnShare         = (Button)      findViewById(R.id.btn_share);
        mProgress         = (ProgressBar) findViewById(R.id.progress);

        mZimPath = getIntent().getStringExtra("zim_path");
        if (mZimPath == null) {
            Toast.makeText(this, "No ZIM file specified", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupWebView();
        setupListeners();
        openZim(mZimPath);
    }

    private void setupWebView() {
        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setTextZoom(mZoomLevels[mZoomIndex]);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setAllowFileAccess(false);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(ZimHttpServer.BASE_URL)) {
                    view.loadUrl(url);
                    return true;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mCurrentUrl = url;
                mProgress.setVisibility(View.VISIBLE);
                mProgress.setProgress(15);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mCurrentUrl = url;
                mProgress.setVisibility(View.GONE);

                String title = view.getTitle();
                if (title != null && !title.isEmpty() && !title.equals("about:blank")) {
                    mCurrentTitle = title;
                    mEditSearch.setHint(title);
                }

                if (mIsNightMode) {
                    injectNightMode();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                Log.e(TAG, "WebView error " + errorCode + ": " + description + " @ " + failingUrl);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgress.setProgress(newProgress);
                if (newProgress == 100) {
                    mProgress.setVisibility(View.GONE);
                }
            }
        });
    }

    private void setupListeners() {
        mBtnBackLibrary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        mBtnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mWebView.loadUrl(ZimHttpServer.BASE_URL + "/");
            }
        });

        mBtnRandom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mWebView.loadUrl(ZimHttpServer.BASE_URL + "/random");
            }
        });

        mBtnNight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleNightMode();
            }
        });

        mBtnZoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleZoom();
            }
        });

        mBtnBookmark.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveBookmark();
            }
        });

        mBtnBookmarksList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBookmarksDialog();
            }
        });

        mBtnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWifiSharingDialog();
            }
        });

        mEditSearch.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent event) {
                String q = mEditSearch.getText().toString().trim();
                if (!q.isEmpty()) {
                    mEditSearch.setText("");
                    mWebView.loadUrl(ZimHttpServer.BASE_URL + "/search?q=" +
                            java.net.URLEncoder.encode(q));
                }
                return true;
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void showWifiSharingDialog() {
        String ip = getLocalIpAddress();
        String shareUrl = "http://" + ip + ":" + ZimHttpServer.PORT + "/";
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Wi-Fi Hotspot Sharing");
        builder.setMessage("Your BlackBerry is hosting this Wikipedia offline!\n\n" +
                "Any device connected to your Wi-Fi or Mobile Hotspot can read it at:\n\n" +
                shareUrl + "\n\n" +
                "Just open that address in Chrome, Safari, or Firefox on your computer or phone.");
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void toggleNightMode() {
        mIsNightMode = !mIsNightMode;
        if (mIsNightMode) {
            mBtnNight.setText("Day");
            mBtnNight.setTextColor(0xFF00B4D8);
            injectNightMode();
            Toast.makeText(this, "Night mode enabled", Toast.LENGTH_SHORT).show();
        } else {
            mBtnNight.setText("Night");
            mBtnNight.setTextColor(0xFFFFFFFF);
            removeNightMode();
            Toast.makeText(this, "Night mode disabled", Toast.LENGTH_SHORT).show();
        }
    }

    private void injectNightMode() {
        String js = "javascript:(function(){" +
                "var s = document.getElementById('kiwix-dark');" +
                "if (!s) {" +
                "  s = document.createElement('style');" +
                "  s.id = 'kiwix-dark';" +
                "  s.innerHTML = 'html, body { background-color: #121212 !important; color: #e0e0e0 !important; } " +
                "                 a { color: #48cae4 !important; } " +
                "                 img { opacity: 0.85 !important; } " +
                "                 table, td, th { background-color: #1c1c1c !important; color: #e0e0e0 !important; border-color: #333 !important; }';" +
                "  document.head.appendChild(s);" +
                "}" +
                "})()";
        mWebView.loadUrl(js);
    }

    private void removeNightMode() {
        String js = "javascript:(function(){" +
                "var s = document.getElementById('kiwix-dark');" +
                "if (s) { s.remove(); }" +
                "})()";
        mWebView.loadUrl(js);
    }

    private void cycleZoom() {
        mZoomIndex = (mZoomIndex + 1) % mZoomLevels.length;
        int zoom = mZoomLevels[mZoomIndex];
        mWebView.getSettings().setTextZoom(zoom);
        mBtnZoom.setText(zoom + "%");
    }

    private void saveBookmark() {
        if (mCurrentUrl.isEmpty() || mCurrentUrl.equals(ZimHttpServer.BASE_URL + "/")) {
            Toast.makeText(this, "Cannot bookmark empty page", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String jsonStr = prefs.getString(PREF_BOOKMARKS_KEY, "[]");
            JSONArray arr = new JSONArray(jsonStr);

            String title = (mCurrentTitle.isEmpty()) ? mCurrentUrl : mCurrentTitle;

            // Check duplicate
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getString("url").equals(mCurrentUrl)) {
                    Toast.makeText(this, "Already bookmarked: " + title, Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            JSONObject item = new JSONObject();
            item.put("title", title);
            item.put("url", mCurrentUrl);
            arr.put(item);

            prefs.edit().putString(PREF_BOOKMARKS_KEY, arr.toString()).apply();
            Toast.makeText(this, "Bookmarked: " + title, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving bookmark", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarksDialog() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String jsonStr = prefs.getString(PREF_BOOKMARKS_KEY, "[]");
            final JSONArray arr = new JSONArray(jsonStr);

            if (arr.length() == 0) {
                Toast.makeText(this, "No bookmarks saved yet", Toast.LENGTH_SHORT).show();
                return;
            }

            final String[] titles = new String[arr.length()];
            final String[] urls   = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                titles[i] = obj.getString("title");
                urls[i]   = obj.getString("url");
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Saved Bookmarks (" + arr.length() + ")");
            builder.setItems(titles, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mWebView.loadUrl(urls[which]);
                }
            });
            builder.setNegativeButton("Close", null);
            builder.setNeutralButton("Clear All", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putString(PREF_BOOKMARKS_KEY, "[]").apply();
                    Toast.makeText(ReaderActivity.this, "Bookmarks cleared", Toast.LENGTH_SHORT).show();
                }
            });
            builder.show();
        } catch (Exception e) {
            Toast.makeText(this, "Error loading bookmarks", Toast.LENGTH_SHORT).show();
        }
    }

    private void openZim(final String path) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mServer != null) {
                        try { mServer.stop(); } catch (Exception ignored) {}
                    }
                    if (mReader != null) {
                        try { mReader.close(); } catch (Exception ignored) {}
                    }

                    mReader = new ZimReader(path);
                    mServer = new ZimHttpServer(mReader);
                    mServer.start();

                    Log.i(TAG, "ZIM server running on " + ZimHttpServer.BASE_URL);
                    Thread.sleep(200);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mWebView.loadUrl(ZimHttpServer.BASE_URL + "/");
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Failed to open ZIM: " + path, e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ReaderActivity.this,
                                    "Failed to open ZIM: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Physical BlackBerry Keyboard Shortcuts
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_R) {
            mWebView.loadUrl(ZimHttpServer.BASE_URL + "/random");
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_H) {
            mWebView.loadUrl(ZimHttpServer.BASE_URL + "/");
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_D) {
            toggleNightMode();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_B) {
            saveBookmark();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_L) {
            showBookmarksDialog();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_W) {
            showWifiSharingDialog();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS) {
            cycleZoom();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event.isShiftPressed()) {
                mWebView.pageUp(false);
            } else {
                mWebView.pageDown(false);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_T) {
            mWebView.scrollTo(0, 0);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mServer != null) {
            try { mServer.stop(); } catch (Exception ignored) {}
        }
        if (mReader != null) {
            try { mReader.close(); } catch (Exception ignored) {}
        }
    }
}
