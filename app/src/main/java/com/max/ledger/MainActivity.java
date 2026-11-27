package com.max.ledger;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int FILE_REQ = 42;        // <input type=file> (profile photo, restore file)
    private static final int SAVE_REQ = 43;        // "Save to file" picker
    private static final int SCAN_CAMERA_REQ = 44; // scan: take a photo
    private static final int SCAN_FILE_REQ = 45;   // scan: photo or PDF from the device
    private static final int SNAP_MD_REQ = 46;     // budget snapshot: where the Markdown file goes
    private static final int SNAP_JSON_REQ = 47;   // budget snapshot: where the JSON file goes

    /** Automatic backups go to Documents/Ledger (visible in My Files, kept after uninstall). */
    static final String BACKUP_DIR = "Ledger";
    static final int BACKUPS_KEPT = 8;
    private static final int SCAN_MAX_EDGE = 2600; // px; plenty for OCR, keeps memory low
    private static final int PDF_MAX_PAGES = 3;

    private WebView wv;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSaveText;   // backup text waiting for the user to pick a place
    private String cameraPath;        // temp photo for a camera scan
    private Uri pendingSnapMd;        // Markdown file picked, waiting for the JSON pick
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService aiExec = Executors.newSingleThreadExecutor(); // Claude calls, so a slow answer never holds up backups or scans
    private volatile boolean aiBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            cameraPath = savedInstanceState.getString("cameraPath");
            String pm = savedInstanceState.getString("pendingSnapMd");
            if (pm != null) pendingSnapMd = Uri.parse(pm);
        }

        Notify.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); // the app page is an asset; it never needs other files
        wv.setWebViewClient(new WebViewClient());

        // Alerts/confirms + the file picker for <input type=file> (photo, restore from file).
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean imagesOnly = true;
                String[] types = params.getAcceptTypes();
                if (types == null || types.length == 0) imagesOnly = false;
                else for (String t : types) if (t == null || !t.startsWith("image/")) imagesOnly = false;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(imagesOnly ? "image/*" : "*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, imagesOnly ? "Choose photo" : "Choose file"), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        wv.addJavascriptInterface(new Bridge(), "Android");
        wv.setBackgroundColor(0xFF0C0F14);

        // Keep content out from under the status bar / gesture bar (Android 15+ edge-to-edge)
        wv.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        wv.loadUrl("file:///android_asset/ledger.html");
        setContentView(wv);

        // Back closes the open sheet first. Android 13+ uses the new back callback
        // (on Android 16, onBackPressed is no longer called at all).
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        scheduleDaily(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraPath != null) out.putString("cameraPath", cameraPath);
        if (pendingSnapMd != null) out.putString("pendingSnapMd", pendingSnapMd.toString());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (wv == null) { moveTaskToBack(true); return; }
        wv.evaluateJavascript("(window.ledgerBack&&window.ledgerBack())?1:0", res -> {
            if (!"1".equals(res)) moveTaskToBack(true); // nothing open: leave like Home
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == FILE_REQ) {
            if (fileCallback != null) {
                Uri[] out = null;
                if (result == RESULT_OK && data != null && data.getData() != null)
                    out = new Uri[]{ data.getData() };
                fileCallback.onReceiveValue(out);
                fileCallback = null;
            }
        } else if (req == SAVE_REQ) {
            final String text = pendingSaveText;
            pendingSaveText = null;
            if (result != RESULT_OK || data == null || data.getData() == null || text == null) {
                js("window.ledgerBackupStatus&&window.ledgerBackupStatus(" + JSONObject.quote("Save cancelled") + ")");
                return;
            }
            final Uri uri = data.getData();
            io.execute(() -> {
                String msg;
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new Exception("couldn't open the file");
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    msg = "✓ Backup saved";
                } catch (Exception e) {
                    msg = "✗ Couldn't save: " + e.getMessage();
                }
                js("window.ledgerBackupStatus&&window.ledgerBackupStatus(" + JSONObject.quote(msg) + ")");
            });
        } else if (req == SNAP_MD_REQ) {
            if (result != RESULT_OK || data == null || data.getData() == null) {
                snapStatus(false, "Cancelled. Nothing changed.");
                return;
            }
            Uri md = data.getData();
            keepAccess(md, data.getFlags());
            pendingSnapMd = md;
            // second pick: the picker opens in the same folder, so it's usually one tap
            snapPick(SNAP_JSON_REQ, "application/json", "ledger-budget.json");
        } else if (req == SNAP_JSON_REQ) {
            Uri md = pendingSnapMd;
            pendingSnapMd = null;
            if (result != RESULT_OK || data == null || data.getData() == null || md == null) {
                if (md != null) dropAccess(md);
                snapStatus(false, "Cancelled. Nothing changed.");
                return;
            }
            Uri json = data.getData();
            keepAccess(json, data.getFlags());
            Uri oldMd = snapUri("snapMd"), oldJson = snapUri("snapJson");
            snapPrefs().edit()
                    .putString("snapMd", md.toString()).putString("snapMdName", displayName(md, "ledger-budget.md"))
                    .putString("snapJson", json.toString()).putString("snapJsonName", displayName(json, "ledger-budget.json"))
                    .apply();
            if (oldMd != null && !oldMd.equals(md)) dropAccess(oldMd);
            if (oldJson != null && !oldJson.equals(json)) dropAccess(oldJson);
            // the page writes the first snapshot right away
            js("window.ledgerSnapshotPicked&&window.ledgerSnapshotPicked()");
        } else if (req == SCAN_CAMERA_REQ) {
            final String path = cameraPath;
            cameraPath = null;
            if (result != RESULT_OK || path == null || !new File(path).exists() || new File(path).length() == 0) {
                if (path != null) new File(path).delete();
                scanReply("{\"cancel\":true}");
                return;
            }
            io.execute(() -> runScan(Uri.fromFile(new File(path)), false, new File(path)));
        } else if (req == SCAN_FILE_REQ) {
            if (result != RESULT_OK || data == null || data.getData() == null) {
                scanReply("{\"cancel\":true}");
                return;
            }
            final Uri uri = data.getData();
            final String type = getContentResolver().getType(uri);
            final boolean pdf = "application/pdf".equals(type)
                    || uri.toString().toLowerCase().endsWith(".pdf");
            io.execute(() -> runScan(uri, pdf, null));
        }
    }

    // ---------- helpers ----------
    private void aiReply(boolean ok, String text) {
        js("window.ledgerAIResult&&window.ledgerAIResult(" + ok + "," + JSONObject.quote(text) + ")");
    }

    private void js(final String code) {
        runOnUiThread(() -> { if (wv != null) wv.evaluateJavascript(code, null); });
    }

    private void scanReply(String json) {
        // U+2028/2029 are legal in JSON but can break older JS parsers
        String safe = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        js("window.ledgerScanResult&&window.ledgerScanResult(" + safe + ")");
    }

    private static String errJson(String msg) {
        try { return new JSONObject().put("error", msg).toString(); }
        catch (Exception e) { return "{\"error\":\"scan failed\"}"; }
    }

    // ---------- scanning (ML Kit, on the phone, nothing uploaded) ----------
    private void startScan(String source) {
        try {
            if ("camera".equals(source)) {
                File dir = new File(getCacheDir(), "scan");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("no space for the photo");
                File f = new File(dir, "scan-" + System.currentTimeMillis() + ".jpg");
                cameraPath = f.getAbsolutePath();
                Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, out);
                i.setClipData(ClipData.newRawUri("scan", out));
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, SCAN_CAMERA_REQ);
            } else {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                startActivityForResult(Intent.createChooser(i, "Choose a photo or PDF"), SCAN_FILE_REQ);
            }
        } catch (ActivityNotFoundException e) {
            cameraPath = null;
            scanReply(errJson("no camera app found"));
        } catch (Exception e) {
            cameraPath = null;
            scanReply(errJson(String.valueOf(e.getMessage())));
        }
    }

    /** Runs on the io thread. Reads the image or the first pages of a PDF, sends lines to JS. */
    private void runScan(Uri uri, boolean pdf, File deleteAfter) {
        TextRecognizer rec = null;
        File pdfCopy = null;
        try {
            List<Bitmap> pages = new ArrayList<>();
            if (pdf) {
                pdfCopy = new File(getCacheDir(), "scan-" + System.currentTimeMillis() + ".pdf");
                copy(uri, pdfCopy); // PdfRenderer needs a seekable file
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfCopy, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer r = new PdfRenderer(pfd)) {
                    int n = Math.min(PDF_MAX_PAGES, r.getPageCount());
                    for (int p = 0; p < n; p++) {
                        try (PdfRenderer.Page page = r.openPage(p)) {
                            float scale = 2000f / Math.max(1, page.getWidth());
                            int w = Math.round(page.getWidth() * scale), h = Math.round(page.getHeight() * scale);
                            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            new Canvas(b).drawColor(Color.WHITE);
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            pages.add(b);
                        }
                    }
                }
            } else {
                Bitmap b = decodeImage(uri);
                if (b == null) throw new Exception("couldn't open that image");
                pages.add(b);
            }
            if (pages.isEmpty()) throw new Exception("nothing to read");

            rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            JSONArray lines = new JSONArray();
            int yOffset = 0;
            for (Bitmap b : pages) {
                Text text = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)));
                for (Text.TextBlock block : text.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        Rect r = line.getBoundingBox();
                        if (r == null) continue;
                        lines.put(new JSONObject()
                                .put("t", line.getText())
                                .put("x0", r.left).put("y0", r.top + yOffset)
                                .put("x1", r.right).put("y1", r.bottom + yOffset));
                    }
                }
                yOffset += b.getHeight() + 200; // keep pages apart so rows never merge
                b.recycle();
            }
            scanReply(new JSONObject().put("lines", lines).toString());
        } catch (Throwable e) {
            String m = e.getMessage();
            if (e instanceof OutOfMemoryError) m = "the image is too large";
            scanReply(errJson(m == null ? "scan failed" : m));
        } finally {
            if (rec != null) rec.close();
            if (pdfCopy != null) pdfCopy.delete();
            if (deleteAfter != null) deleteAfter.delete(); // the photo isn't kept
        }
    }

    private void copy(Uri from, File to) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new Exception("couldn't open the file");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Decodes a photo at a sensible size and turns it upright using its EXIF orientation. */
    private Bitmap decodeImage(Uri uri) throws Exception {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SCAN_MAX_EDGE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = cr.openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
        if (b == null) return null;

        int rotate = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (or == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
                else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
                else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
            }
        } catch (Exception ignored) {}

        float scale = Math.min(1f, (float) SCAN_MAX_EDGE / Math.max(b.getWidth(), b.getHeight()));
        if (rotate != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotate);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    // ---------- budget snapshot (Markdown + JSON, e.g. in a Google Drive folder) ----------
    // Uses Android's own file picker (Storage Access Framework). Google Drive shows up there,
    // so no Google sign-in or API keys are needed: Max picks the two files once, the app keeps
    // permission to them, and every save overwrites both in place.
    private android.content.SharedPreferences snapPrefs() {
        return getSharedPreferences("ledger", Context.MODE_PRIVATE);
    }

    private Uri snapUri(String key) {
        String u = snapPrefs().getString(key, null);
        return u == null ? null : Uri.parse(u);
    }

    private void snapPick(int req, String mime, String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            if (pendingSnapMd != null) { dropAccess(pendingSnapMd); pendingSnapMd = null; }
            snapStatus(false, "No file picker available on this phone.");
        }
    }

    /** Keeps write access after the app restarts. Drive and local storage both allow it. */
    private void keepAccess(Uri uri, int flags) {
        try {
            int f = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (f == 0) f = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, f);
        } catch (Exception ignored) {
            // provider doesn't offer lasting access; writes still work until the app closes
        }
    }

    private void dropAccess(Uri uri) {
        try {
            getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {}
        return fallback;
    }

    /** Replaces the whole file. "wt" truncates; never plain "w", which can leave old text at the end. */
    private void overwrite(Uri uri, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        OutputStream os;
        try {
            os = getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | java.io.FileNotFoundException modeRefused) {
            os = getContentResolver().openOutputStream(uri, "rwt");
        }
        if (os == null) throw new Exception("couldn't open the file");
        try (OutputStream o = os) { o.write(bytes); }
    }

    private void snapStatus(boolean ok, String msg) {
        js("window.ledgerSnapshotStatus&&window.ledgerSnapshotStatus(" + ok + "," + JSONObject.quote(msg) + ")");
    }

    // ---------- automatic backups in Documents/Ledger ----------
    private static boolean validBackupName(String name) {
        return name != null && name.matches("[A-Za-z0-9._ ()-]{1,100}\\.json");
    }

    private static final class BackupFile {
        final String name; final long size; final Uri uri; final File file;
        BackupFile(String n, long s, Uri u, File f) { name = n; size = s; uri = u; file = f; }
    }

    /** Backups this app wrote, newest first. */
    private List<BackupFile> listAutoBackups() {
        List<BackupFile> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Uri col = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE};
            String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = {Environment.DIRECTORY_DOCUMENTS + "/" + BACKUP_DIR + "%", "ledger-%.json"};
            try (Cursor c = getContentResolver().query(col, proj, sel, args, null)) {
                if (c != null) while (c.moveToNext()) {
                    long id = c.getLong(0);
                    out.add(new BackupFile(c.getString(1), c.getLong(2),
                            android.content.ContentUris.withAppendedId(col, id), null));
                }
            } catch (Exception ignored) {}
        } else {
            File dir = legacyDir();
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs)
                if (f.getName().startsWith("ledger-") && f.getName().endsWith(".json"))
                    out.add(new BackupFile(f.getName(), f.length(), null, f));
        }
        // names start with ledger-auto-YYYY-MM-DD_HHMM, so name order is time order
        Collections.sort(out, (a, b) -> b.name.compareTo(a.name));
        return out;
    }

    private File legacyDir() {
        File d = new File(getExternalFilesDir(null), BACKUP_DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void writeAutoBackup(String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + BACKUP_DIR + "/");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new Exception("couldn't create the file");
            try (OutputStream os = cr.openOutputStream(uri, "w")) {
                if (os == null) throw new Exception("couldn't open the file");
                os.write(bytes);
            } catch (Exception e) {
                cr.delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } else {
            try (OutputStream os = new FileOutputStream(new File(legacyDir(), name))) { os.write(bytes); }
        }
        // keep the newest few
        List<BackupFile> all = listAutoBackups();
        for (int i = BACKUPS_KEPT; i < all.size(); i++) {
            BackupFile f = all.get(i);
            try {
                if (f.uri != null) getContentResolver().delete(f.uri, null, null);
                else if (f.file != null) f.file.delete();
            } catch (Exception ignored) {}
        }
    }

    private String readAutoBackup(String name) throws Exception {
        for (BackupFile f : listAutoBackups()) {
            if (!f.name.equals(name)) continue;
            try (InputStream in = f.uri != null ? getContentResolver().openInputStream(f.uri)
                    : new java.io.FileInputStream(f.file)) {
                if (in == null) return "";
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    public static void scheduleDaily(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 9);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                cal.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    /** Everything the HTML app can call as window.Android.* */
    public class Bridge {

        @JavascriptInterface
        public void notify(String title, String body) {
            Notify.post(MainActivity.this, title, body, (title + body).hashCode());
        }

        @JavascriptInterface
        public void setSchedule(String json) {
            getSharedPreferences("ledger", Context.MODE_PRIVATE)
                    .edit().putString("schedule", json).apply();
        }

        /** Open a web, mail or phone link. Bank/broker apps that claim their https links open directly. */
        @JavascriptInterface
        public void openUrl(String url) {
            try {
                Uri u = Uri.parse(url);
                String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (!(sch.equals("https") || sch.equals("http") || sch.equals("mailto") || sch.equals("tel"))) return;
                Intent i = new Intent(Intent.ACTION_VIEW, u);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
        }

        /** "Save to file": asks where to save, then writes the text there. */
        @JavascriptInterface
        public void saveBackup(final String name, final String text) {
            runOnUiThread(() -> {
                pendingSaveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, SAVE_REQ);
                } catch (Exception e) {
                    pendingSaveText = null;
                    js("window.ledgerBackupStatus&&window.ledgerBackupStatus(" + JSONObject.quote("✗ No file picker available") + ")");
                }
            });
        }

        /** Writes a backup to Documents/Ledger without asking, keeping the newest 8. */
        @JavascriptInterface
        public void autoBackup(final String name, final String text) {
            if (!validBackupName(name) || text == null) return;
            io.execute(() -> {
                try {
                    writeAutoBackup(name, text);
                    js("window.ledgerAutoBackupStatus&&window.ledgerAutoBackupStatus(true,'')");
                } catch (Exception e) {
                    js("window.ledgerAutoBackupStatus&&window.ledgerAutoBackupStatus(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            });
        }

        /** JSON list of automatic backups, newest first: [{name,size}] */
        @JavascriptInterface
        public String listBackups() {
            JSONArray a = new JSONArray();
            try {
                for (BackupFile f : listAutoBackups())
                    a.put(new JSONObject().put("name", f.name).put("size", f.size));
            } catch (Exception ignored) {}
            return a.toString();
        }

        @JavascriptInterface
        public String readBackup(String name) {
            if (!validBackupName(name)) return "";
            try { return readAutoBackup(name); } catch (Exception e) { return ""; }
        }

        /** Budget snapshot: pick (or re-pick) the Markdown and JSON files, e.g. in a Drive folder. */
        @JavascriptInterface
        public void snapshotPick() {
            runOnUiThread(() -> snapPick(SNAP_MD_REQ, "text/markdown", "ledger-budget.md"));
        }

        /** {"on":bool,"md":name,"json":name} */
        @JavascriptInterface
        public String snapshotInfo() {
            try {
                android.content.SharedPreferences p = snapPrefs();
                boolean on = p.getString("snapMd", null) != null && p.getString("snapJson", null) != null;
                return new JSONObject().put("on", on)
                        .put("md", p.getString("snapMdName", "")).put("json", p.getString("snapJsonName", ""))
                        .toString();
            } catch (Exception e) { return "{\"on\":false}"; }
        }

        /** Overwrites both snapshot files. Answers via window.ledgerSnapshotStatus(ok, message). */
        @JavascriptInterface
        public void snapshotWrite(final String md, final String json) {
            final Uri mdUri = snapUri("snapMd"), jsonUri = snapUri("snapJson");
            if (mdUri == null || jsonUri == null || md == null || json == null) return;
            io.execute(() -> {
                try {
                    overwrite(mdUri, md);
                    overwrite(jsonUri, json);
                    snapStatus(true, "");
                } catch (SecurityException e) {
                    snapStatus(false, "Ledger lost access to the snapshot files. Choose where to save them again.");
                } catch (Exception e) {
                    snapStatus(false, "Couldn't update the snapshot: " + e.getMessage());
                }
            });
        }

        /** Stops writing snapshots. The files already written stay where they are. */
        @JavascriptInterface
        public void snapshotOff() {
            Uri md = snapUri("snapMd"), json = snapUri("snapJson");
            if (md != null) dropAccess(md);
            if (json != null) dropAccess(json);
            snapPrefs().edit().remove("snapMd").remove("snapMdName")
                    .remove("snapJson").remove("snapJsonName").apply();
        }

        /** Scan a paystub or receipt: source is "camera" or "file". Answers via window.ledgerScanResult. */
        @JavascriptInterface
        public void scanDocument(final String source) {
            runOnUiThread(() -> startScan(source));
        }

        // ---------- AI for Analyze: Claude or Hermes ----------
        // Keys go in once and are never handed back to the page: only whether one is set
        // and its last 4 characters.

        /** {"provider":"claude|hermes","claude":{set,tail,model},"hermes":{set,tail,url}} */
        @JavascriptInterface
        public String aiStatus() {
            try {
                AiKeyStore ks = new AiKeyStore(MainActivity.this);
                boolean c = ks.has(AiKeyStore.CLAUDE), h = ks.has(AiKeyStore.HERMES);
                return new JSONObject().put("provider", ks.provider())
                        .put("claude", new JSONObject().put("set", c).put("tail", c ? ks.tail(AiKeyStore.CLAUDE) : "")
                                .put("model", ClaudeAdvisor.MODEL))
                        .put("hermes", new JSONObject().put("set", h && !ks.hermesUrl().isEmpty())
                                .put("tail", h ? ks.tail(AiKeyStore.HERMES) : "").put("url", ks.hermesUrl()))
                        .toString();
            } catch (Exception e) { return "{\"provider\":\"claude\"}"; }
        }

        @JavascriptInterface
        public void aiSetProvider(String p) {
            new AiKeyStore(MainActivity.this).setProvider(p);
        }

        /** Saves the Claude key (encrypted, this phone only). False if it doesn't look like an Anthropic key. */
        @JavascriptInterface
        public boolean aiSetKey(String key) {
            return new AiKeyStore(MainActivity.this).set(key);
        }

        @JavascriptInterface
        public void aiClearKey() {
            new AiKeyStore(MainActivity.this).clear();
        }

        /** Saves the Hermes address and key (key blank = keep the saved one). Returns "" or what's wrong. */
        @JavascriptInterface
        public String aiSetHermes(String url, String key) {
            AiKeyStore ks = new AiKeyStore(MainActivity.this);
            String bad = HermesAdvisor.checkUrl(url);
            if (!bad.isEmpty()) return bad;
            boolean newKey = key != null && !key.trim().isEmpty();
            if (!newKey && !ks.has(AiKeyStore.HERMES)) return "Add the key you set as API_SERVER_KEY in Hermes.";
            if (newKey && key.trim().length() < 8) return "That key looks too short.";
            ks.setHermes(url.trim(), key);
            return "";
        }

        @JavascriptInterface
        public void aiClearHermes() {
            new AiKeyStore(MainActivity.this).clearHermes();
        }

        /** Sends the budget summary to the chosen AI. Answers via window.ledgerAIResult(ok, text). */
        @JavascriptInterface
        public void askAI(final String budgetJson) {
            if (aiBusy) return;
            final AiKeyStore ks = new AiKeyStore(MainActivity.this);
            final boolean hermes = "hermes".equals(ks.provider());
            final String key = ks.getSecret(hermes ? AiKeyStore.HERMES : AiKeyStore.CLAUDE);
            final String url = ks.hermesUrl();
            if (key == null || (hermes && url.isEmpty())) {
                aiReply(false, hermes ? "Hermes isn't set up yet." : "No Claude API key saved yet.");
                return;
            }
            aiBusy = true;
            final String json = budgetJson == null ? "{}" : budgetJson;
            aiExec.execute(() -> {
                try {
                    ClaudeAdvisor.Result r = hermes ? HermesAdvisor.ask(url, key, json) : ClaudeAdvisor.ask(key, json);
                    aiReply(r.ok, r.text);
                } finally {
                    aiBusy = false;
                }
            });
        }
    }
}
