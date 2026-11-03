package com.mostofa.dailyamaltracker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 101;
    private static final int SAVE_FILE = 102;

    private WebView web;
    private WebView printWebView;
    private ValueCallback<Uri[]> chooser;
    private byte[] pendingFile;
    private String pendingName;
    private String pendingMime = "application/octet-stream";

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveBackup(String filename, String base64) {
                saveFileInternal(filename, "application/json", base64);
            }

            @JavascriptInterface
            public void saveFile(String filename, String mimeType, String base64) {
                saveFileInternal(filename, mimeType, base64);
            }

            @JavascriptInterface
            public void printHtml(String html) {
                if (html == null || html.length() > 10 * 1024 * 1024) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Report is too large to print", Toast.LENGTH_LONG).show());
                    return;
                }
                runOnUiThread(() -> printReport(html));
            }

            private void saveFileInternal(String filename, String mimeType, String base64) {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    if (bytes.length > 50 * 1024 * 1024) {
                        throw new Exception("File too large");
                    }
                    runOnUiThread(() -> {
                        pendingFile = bytes;
                        pendingName = filename == null ? "Daily_Amal_Report" :
                            filename.replaceAll("[^a-zA-Z0-9_.-]", "_");
                        pendingMime = (mimeType == null || mimeType.trim().isEmpty())
                            ? "application/octet-stream" : mimeType;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(pendingMime);
                        intent.putExtra(Intent.EXTRA_TITLE, pendingName);
                        startActivityForResult(intent, SAVE_FILE);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "ফাইল তৈরি/সংরক্ষণ করা যায়নি", Toast.LENGTH_LONG).show());
                }
            }
        }, "DailyAmalAndroid");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                WebView v, ValueCallback<Uri[]> cb, FileChooserParams params
            ) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/json",
                    "text/csv",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/pdf"
                });
                startActivityForResult(intent, PICK_FILE);
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                WebView v, WebResourceRequest request
            ) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                            "Browser unavailable", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return !"file".equals(scheme);
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private void printReport(String html) {
        try {
            printWebView = new WebView(this);
            WebSettings settings = printWebView.getSettings();
            settings.setJavaScriptEnabled(false);
            printWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
                    if (manager == null) {
                        Toast.makeText(MainActivity.this,
                            "Print service unavailable", Toast.LENGTH_LONG).show();
                        return;
                    }
                    PrintAttributes attributes = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build();
                    manager.print("Daily Amal Tracker Report",
                        view.createPrintDocumentAdapter("Daily Amal Tracker Report"),
                        attributes);
                }
            });
            printWebView.loadDataWithBaseURL(
                "https://daily-amal.local/", html, "text/html", "UTF-8", null
            );
        } catch (Exception e) {
            Toast.makeText(this, "রিপোর্ট প্রিন্ট করা যায়নি",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request == PICK_FILE) {
            if (chooser != null) {
                chooser.onReceiveValue(
                    result == RESULT_OK && data != null && data.getData() != null
                        ? new Uri[]{data.getData()} : null
                );
                chooser = null;
            }
        }

        if (request == SAVE_FILE) {
            if (result == RESULT_OK && data != null
                && data.getData() != null && pendingFile != null) {
                try (OutputStream os =
                    getContentResolver().openOutputStream(data.getData())) {
                    if (os == null) throw new Exception("Output stream unavailable");
                    os.write(pendingFile);
                    Toast.makeText(this, "ফাইল সংরক্ষণ হয়েছে",
                        Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "ফাইল সংরক্ষণ করা যায়নি",
                        Toast.LENGTH_LONG).show();
                }
            }
            pendingFile = null;
            pendingName = null;
            pendingMime = "application/octet-stream";
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
