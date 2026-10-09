package com.kiwixbb10;

import android.app.Activity;
import android.content.Intent;
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

import java.io.IOException;

/**
 * Reader screen: starts the local ZIM HTTP server and loads content in a WebView.
 */
public class ReaderActivity extends Activity {
    private static final String TAG = "ReaderActivity";

    private WebView     mWebView;
    private EditText    mEditSearch;
    private Button      mBtnBackLibrary;
    private Button      mBtnHome;
    private ProgressBar mProgress;

    private ZimReader     mReader;
    private ZimHttpServer mServer;
    private String        mZimPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        mWebView        = (WebView)     findViewById(R.id.webview);
        mEditSearch     = (EditText)    findViewById(R.id.edit_search);
        mBtnBackLibrary = (Button)      findViewById(R.id.btn_back_library);
        mBtnHome        = (Button)      findViewById(R.id.btn_home);
        mProgress       = (ProgressBar) findViewById(R.id.progress);

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
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setAllowFileAccess(false);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep all navigation inside our WebView
                if (url.startsWith(ZimHttpServer.BASE_URL)) {
                    view.loadUrl(url);
                    return true;
                }
                // External links — ignore or could open browser
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mProgress.setVisibility(View.VISIBLE);
                mProgress.setProgress(10);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mProgress.setVisibility(View.GONE);
                // Update search bar with current page title
                String title = view.getTitle();
                if (title != null && !title.isEmpty() &&
                        !title.equals("about:blank")) {
                    mEditSearch.setHint(title);
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

        mEditSearch.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v,
                    int actionId, KeyEvent event) {
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

    private void openZim(final String path) {
        // Open ZIM and start server on a background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Close any existing reader/server
                    if (mServer != null) {
                        try { mServer.stop(); } catch (Exception ignored) {}
                    }
                    if (mReader != null) {
                        try { mReader.close(); } catch (Exception ignored) {}
                    }

                    mReader = new ZimReader(path);
                    mServer = new ZimHttpServer(mReader);
                    mServer.start();

                    Log.i(TAG, "ZIM server started on port " + ZimHttpServer.PORT);

                    // Small delay for server to bind
                    Thread.sleep(200);

                    // Load main page
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
        // BB10 back button navigates WebView history
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
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
