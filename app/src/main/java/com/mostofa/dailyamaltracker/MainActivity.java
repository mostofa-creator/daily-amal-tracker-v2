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

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 101;
    private static final int SAVE_FILE = 102;

    private static final String WEB_CLIENT_ID =
        "489202598079-h9rhap0v03234hflrc9kk8m5ogb1k8o9.apps.googleusercontent.com";

    private WebView web;
    private WebView printWebView;

    private ValueCallback<Uri[]> chooser;
    private byte[] pendingFile;
    private String pendingName;
    private String pendingMime = "application/octet-stream";

    private FirebaseAuth firebaseAuth;
    private CredentialManager credentialManager;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        firebaseAuth = FirebaseAuth.getInstance();
        credentialManager = CredentialManager.create(this);

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
                    runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Report is too large to print",
                        Toast.LENGTH_LONG
                    ).show());
                    return;
                }

                runOnUiThread(() -> printReport(html));
            }

            @JavascriptInterface
            public void startGoogleSignIn() {
                runOnUiThread(() -> signInWithGoogle());
            }

            @JavascriptInterface
            public void signOut() {
                runOnUiThread(() -> {
                    firebaseAuth.signOut();
                    notifyAuthState();
                });
            }

            @JavascriptInterface
            public String getAuthState() {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                return createAuthJson(user);
            }

            private void saveFileInternal(
                String filename,
                String mimeType,
                String base64
            ) {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                    if (bytes.length > 50 * 1024 * 1024) {
                        throw new Exception("File too large");
                    }

                    runOnUiThread(() -> {
                        pendingFile = bytes;

                        pendingName = filename == null
                            ? "Daily_Amal_Report"
                            : filename.replaceAll(
                                "[^a-zA-Z0-9_.-]", "_"
                            );

                        pendingMime =
                            (mimeType == null || mimeType.trim().isEmpty())
                            ? "application/octet-stream"
                            : mimeType;

                        Intent intent =
                            new Intent(Intent.ACTION_CREATE_DOCUMENT);

                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(pendingMime);
                        intent.putExtra(Intent.EXTRA_TITLE, pendingName);

                        startActivityForResult(intent, SAVE_FILE);
                    });

                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "ফাইল তৈরি/সংরক্ষণ করা যায়নি",
                        Toast.LENGTH_LONG
                    ).show());
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
                if (chooser != null) {
                    chooser.onReceiveValue(null);
                }

                chooser = cb;

                Intent intent =
                    new Intent(Intent.ACTION_OPEN_DOCUMENT);

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
                WebView v,
                WebResourceRequest request
            ) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();

                if ("https".equals(scheme) || "http".equals(scheme)) {
                    try {
                        startActivity(
                            new Intent(Intent.ACTION_VIEW, uri)
                        );
                    } catch (Exception e) {
                        Toast.makeText(
                            MainActivity.this,
                            "Browser unavailable",
                            Toast.LENGTH_SHORT
                        ).show();
                    }

                    return true;
                }

                return !"file".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                notifyAuthState();
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }


    // GOOGLE LOGIN

    private void signInWithGoogle() {

        GetGoogleIdOption googleIdOption =
            new GetGoogleIdOption.Builder()
                .setServerClientId(WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false)
                .build();

        GetCredentialRequest request =
            new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();

        Executor mainExecutor = command ->
            runOnUiThread(command);

        credentialManager.getCredentialAsync(
            request,
            this,
            null,
            mainExecutor,
            new CredentialManagerCallback<
                GetCredentialResponse,
                GetCredentialException
            >() {

                @Override
                public void onResult(GetCredentialResponse result) {
                    handleGoogleCredential(result.getCredential());
                }

                @Override
                public void onError(GetCredentialException e) {
                    Toast.makeText(
                        MainActivity.this,
                        "Google Login করা যায়নি: " + e.getMessage(),
                        Toast.LENGTH_LONG
                    ).show();
                }
            }
        );
    }


    private void handleGoogleCredential(Credential credential) {

        if (credential instanceof CustomCredential
            && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                .equals(credential.getType())) {

            try {
                GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(
                        ((CustomCredential) credential).getData()
                    );

                firebaseAuthWithGoogle(
                    googleCredential.getIdToken()
                );

            } catch (Exception e) {
                Toast.makeText(
                    this,
                    "Google Account তথ্য পাওয়া যায়নি",
                    Toast.LENGTH_LONG
                ).show();
            }

        } else {
            Toast.makeText(
                this,
                "সঠিক Google Credential পাওয়া যায়নি",
                Toast.LENGTH_LONG
            ).show();
        }
    }


    private void firebaseAuthWithGoogle(String idToken) {

        AuthCredential credential =
            GoogleAuthProvider.getCredential(idToken, null);

        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener(this, task -> {

                if (task.isSuccessful()) {

                    Toast.makeText(
                        this,
                        "Google Login সফল হয়েছে",
                        Toast.LENGTH_SHORT
                    ).show();

                    notifyAuthState();

                } else {

                    Toast.makeText(
                        this,
                        "Firebase Login ব্যর্থ: " +
                            (task.getException() == null
                                ? "Unknown error"
                                : task.getException().getMessage()),
                        Toast.LENGTH_LONG
                    ).show();

                    notifyAuthState();
                }
            });
    }


    // LOGIN STATUS

    private String createAuthJson(FirebaseUser user) {

        try {
            JSONObject json = new JSONObject();

            if (user == null) {
                json.put("signedIn", false);
            } else {
                json.put("signedIn", true);
                json.put("uid", user.getUid());
                json.put("email", user.getEmail());
                json.put("displayName", user.getDisplayName());
            }

            return json.toString();

        } catch (Exception e) {
            return "{\"signedIn\":false}";
        }
    }


    private void notifyAuthState() {

        if (web == null) return;

        FirebaseUser user = firebaseAuth.getCurrentUser();

        String json = createAuthJson(user);

        String script =
            "if(typeof window.onNativeAuthChanged === 'function')" +
            "{window.onNativeAuthChanged(" +
            JSONObject.quote(json) +
            ");}";

        web.post(() -> web.evaluateJavascript(script, null));
    }


    // REPORT PRINT

    private void printReport(String html) {

        try {
            printWebView = new WebView(this);

            WebSettings settings = printWebView.getSettings();
            settings.setJavaScriptEnabled(false);

            printWebView.setWebViewClient(new WebViewClient() {

                @Override
                public void onPageFinished(WebView view, String url) {

                    PrintManager manager =
                        (PrintManager) getSystemService(PRINT_SERVICE);

                    if (manager == null) {
                        Toast.makeText(
                            MainActivity.this,
                            "Print service unavailable",
                            Toast.LENGTH_LONG
                        ).show();
                        return;
                    }

                    PrintAttributes attributes =
                        new PrintAttributes.Builder()
                            .setMediaSize(
                                PrintAttributes.MediaSize.ISO_A4
                            )
                            .setMinMargins(
                                PrintAttributes.Margins.NO_MARGINS
                            )
                            .build();

                    manager.print(
                        "Daily Amal Tracker Report",
                        view.createPrintDocumentAdapter(
                            "Daily Amal Tracker Report"
                        ),
                        attributes
                    );
                }
            });

            printWebView.loadDataWithBaseURL(
                "https://daily-amal.local/",
                html,
                "text/html",
                "UTF-8",
                null
            );

        } catch (Exception e) {
            Toast.makeText(
                this,
                "রিপোর্ট প্রিন্ট করা যায়নি",
                Toast.LENGTH_LONG
            ).show();
        }
    }


    // FILE IMPORT / EXPORT

    @Override
    protected void onActivityResult(
        int request,
        int result,
        Intent data
    ) {
        super.onActivityResult(request, result, data);

        if (request == PICK_FILE) {

            if (chooser != null) {
                chooser.onReceiveValue(
                    result == RESULT_OK
                        && data != null
                        && data.getData() != null
                        ? new Uri[]{data.getData()}
                        : null
                );

                chooser = null;
            }
        }

        if (request == SAVE_FILE) {

            if (result == RESULT_OK
                && data != null
                && data.getData() != null
                && pendingFile != null) {

                try (OutputStream os =
                    getContentResolver().openOutputStream(
                        data.getData()
                    )) {

                    if (os == null) {
                        throw new Exception(
                            "Output stream unavailable"
                        );
                    }

                    os.write(pendingFile);

                    Toast.makeText(
                        this,
                        "ফাইল সংরক্ষণ হয়েছে",
                        Toast.LENGTH_SHORT
                    ).show();

                } catch (Exception e) {

                    Toast.makeText(
                        this,
                        "ফাইল সংরক্ষণ করা যায়নি",
                        Toast.LENGTH_LONG
                    ).show();
                }
            }

            pendingFile = null;
            pendingName = null;
            pendingMime = "application/octet-stream";
        }
    }


    @Override
    public void onBackPressed() {

        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
