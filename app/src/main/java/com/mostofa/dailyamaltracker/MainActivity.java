
package com.mostofa.dailyamaltracker;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.webkit.*;
import android.widget.Toast;
import android.util.Base64;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 101;
    private static final int SAVE_BACKUP = 102;

    private WebView web;
    private ValueCallback<Uri[]> chooser;
    private byte[] pendingBackup;
    private String pendingName;

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
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    if (bytes.length > 50 * 1024 * 1024) {
                        throw new Exception("Backup too large");
                    }

                    runOnUiThread(() -> {
                        pendingBackup = bytes;
                        pendingName = filename.replaceAll(
                            "[^a-zA-Z0-9_.-]", "_"
                        );

                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        intent.putExtra(Intent.EXTRA_TITLE, pendingName);
                        startActivityForResult(intent, SAVE_BACKUP);
                    });
                } catch (Exception e) {
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Backup export failed",
                            Toast.LENGTH_LONG).show()
                    );
                }
            }
        }, "DailyAmalAndroid");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                WebView v,
                ValueCallback<Uri[]> cb,
                FileChooserParams params
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

        if (request == SAVE_BACKUP) {
            if (result == RESULT_OK && data != null
                && data.getData() != null && pendingBackup != null) {
                try (OutputStream os =
                    getContentResolver().openOutputStream(data.getData())) {
                    if (os == null) throw new Exception();
                    os.write(pendingBackup);
                    Toast.makeText(this, "Backup saved",
                        Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Could not save backup",
                        Toast.LENGTH_LONG).show();
                }
            }
            pendingBackup = null;
            pendingName = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
