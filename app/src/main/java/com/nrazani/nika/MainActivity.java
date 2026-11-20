package com.nrazani.nika;

import android.app.Activity;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.OutputStream;
import androidx.webkit.WebViewAssetLoader;

// The app entry point is served exclusively from the bundled Android assets.
public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 4102;
    private static final int SAVE_FILE_REQUEST = 4103;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCameraUri;
    private WebViewAssetLoader assetLoader;
    private String pendingFileName;
    private String pendingMimeType;
    private String pendingBase64;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(138, 100, 20));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(25, 25, 25));

        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(250, 247, 240));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equalsIgnoreCase(uri.getScheme())
                    && "appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(MainActivity.this, "برنامه‌ای برای باز کردن این پیوند پیدا نشد.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new NikaBridge(), "NikaAndroid");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                // For image-only inputs (the invoice gallery button), open the
                // device's image gallery directly instead of the generic Downloads picker.
                boolean imageOnly = true;
                String[] accepted = params.getAcceptTypes();
                if (accepted == null || accepted.length == 0) {
                    imageOnly = false;
                } else {
                    boolean hasImage = false;
                    for (String type : accepted) {
                        if (type == null || type.trim().isEmpty()) continue;
                        String normalized = type.toLowerCase(java.util.Locale.ROOT).trim();
                        if (normalized.startsWith("image/")) hasImage = true;
                        else if (!normalized.equals("*/*")) imageOnly = false;
                    }
                    imageOnly = imageOnly && hasImage;
                }

                Intent intent;
                try {
                    if (params.isCaptureEnabled()) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Nika-document-" + System.currentTimeMillis() + ".jpg");
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        pendingCameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (pendingCameraUri == null) throw new ActivityNotFoundException("camera output unavailable");
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } else if (imageOnly) {
                        intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        intent.setType("image/*");
                    } else {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                    }
                    Intent chooser = Intent.createChooser(intent,
                        params.isCaptureEnabled() ? "گرفتن عکس با دوربین" : (imageOnly ? "انتخاب عکس از گالری" : "انتخاب تصویر یا PDF"));
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "برنامه‌ای برای انتخاب فایل پیدا نشد.", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        setContentView(webView);
        if (state == null || webView.restoreState(state) == null) {
            loadLocalApp();
        }
    }

    private final class NikaBridge {
        @JavascriptInterface
        public void saveFile(String name, String mimeType, String base64) {
            if (name == null || base64 == null || base64.isEmpty()) return;
            final String safeName = name.replaceAll("[\\\\/:*?\\\"<>|]", "_");
            final String safeMime = (mimeType == null || mimeType.isEmpty())
                ? "application/octet-stream" : mimeType;

            runOnUiThread(() -> {
                pendingFileName = safeName;
                pendingMimeType = safeMime;
                pendingBase64 = base64;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(pendingMimeType);
                intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, SAVE_FILE_REQUEST);
                } catch (ActivityNotFoundException e) {
                    clearPendingFile();
                    Toast.makeText(MainActivity.this,
                        "پنجره ذخیره فایل در دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void printPage(String jobName) {
            runOnUiThread(() -> printWebView(jobName));
        }
    }

    private void printWebView(String jobName) {
        if (android.os.Build.VERSION.SDK_INT < 19 || webView == null) {
            Toast.makeText(this, "چاپ در این نسخه اندروید پشتیبانی نمی‌شود.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
            if (manager == null) {
                Toast.makeText(this, "سرویس چاپ دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show();
                return;
            }
            String title = (jobName == null || jobName.trim().isEmpty()) ? "Nika report" : jobName;
            PrintDocumentAdapter source = webView.createPrintDocumentAdapter(title);
            PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                @Override
                public void onStart() { source.onStart(); }

                @Override
                public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                     CancellationSignal cancellationSignal, LayoutResultCallback callback,
                                     android.os.Bundle extras) {
                    source.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras);
                }

                @Override
                public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                    CancellationSignal cancellationSignal, WriteResultCallback callback) {
                    source.onWrite(pages, destination, cancellationSignal, callback);
                }

                @Override
                public void onFinish() {
                    source.onFinish();
                    if (webView != null) {
                        webView.post(() -> webView.evaluateJavascript(
                            "window.NikaFinishPrint&&window.NikaFinishPrint()", null));
                    }
                }
            };
            PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                .setResolution(new PrintAttributes.Resolution("nika", "Nika", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
            manager.print(title, adapter, attributes);
        } catch (Exception e) {
            Toast.makeText(this, "آماده‌سازی PDF ناموفق بود: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearPendingFile() {
        pendingFileName = null;
        pendingMimeType = null;
        pendingBase64 = null;
    }

    private void loadLocalApp() {
        // All app files are packaged at the root of app/src/main/assets.
        // WebViewAssetLoader maps /assets/... directly to that directory.
        webView.loadUrl(APP_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_FILE_REQUEST) {
            try {
                if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBase64 != null) {
                    byte[] bytes = Base64.decode(pendingBase64, Base64.DEFAULT);
                    try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                        if (output == null) throw new java.io.IOException("فایل مقصد باز نشد");
                        output.write(bytes);
                        output.flush();
                    }
                    Toast.makeText(this, "فایل با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "ذخیره فایل ناموفق بود: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                clearPendingFile();
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                Uri selected = pendingCameraUri != null ? pendingCameraUri : (data != null ? data.getData() : null);
                if (selected != null) results = new Uri[]{selected};
            } else if (pendingCameraUri != null) {
                try { getContentResolver().delete(pendingCameraUri, null, null); } catch (Exception ignored) {}
            }
            pendingCameraUri = null;
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
