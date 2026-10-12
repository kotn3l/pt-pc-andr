package com.ptport.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONException;
import org.json.JSONObject;

/* The Graphics driver setting's Browse... (src/main.cpp, src/engine/platform/android.cpp BrowseDriver): Android's file picker,
   opened in Download, for an AdrenoTools driver zip (meta.json and the driver's .so). The zip is unpacked into the app's
   private custom_drivers/<the zip's name without .zip>, where libadrenotools can load it, and the game is told the name.
   An activity of its own, in its own task: PTActivity is singleInstance, and an activity it starts for a result may get
   an immediate cancel. */
public class DriverPicker extends Activity {
    private static final int PICK = 1;
    // a zip far larger than any driver is not one
    private static final long MAX_UNPACKED = 512L * 1024 * 1024;

    // the zip was added as `name`, or failed with `error`; both null when the picker was closed (android.cpp)
    private static native void nativePicked(String name, String error);

    static void answer(String name, String error) {
        try {
            nativePicked(name, error);
        } catch (Throwable t) {
            android.util.Log.w("pt", "driver picker: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // made again while the picker is open: its answer still comes to this one
        if (state != null) return;
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
        try {
            startActivityForResult(intent, PICK);
        } catch (ActivityNotFoundException e) {
            answer(null, "this device has no file picker");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        final Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) {
            answer(null, null);
            finish();
            return;
        }
        final ContentResolver resolver = getContentResolver();
        final String name = driverName(displayName(resolver, uri));
        final File base = new File(getFilesDir(), "custom_drivers");
        // the game is back on screen while the zip unpacks; the row shows the driver once it is in place
        new Thread(() -> {
            try {
                unpack(resolver, uri, base, name);
                answer(name, null);
            } catch (Exception e) {
                android.util.Log.w("pt", "driver picker: " + e);
                answer(null, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "pt-driver-unpack").start();
        finish();
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) {
        }
        final String last = uri.getLastPathSegment();
        return last != null ? last.substring(last.lastIndexOf('/') + 1) : "driver";
    }

    // the zip's name without .zip, as the settings show it and pt.ini keeps it: plain characters only, and never one of the
    // setting's own values (system, turnip)
    private static String driverName(String file) {
        String name = file;
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        name = name.replaceAll("[^A-Za-z0-9 ._()+\\-]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 64) name = name.substring(0, 64).trim();
        if (name.isEmpty()) name = "driver";
        if (name.equalsIgnoreCase("system") || name.equalsIgnoreCase("turnip")) name += " (zip)";
        return name;
    }

    // why a picked zip is not added, shown in the settings ("The driver zip was not added: ...")
    private static final class Refused extends IOException {
        Refused(String reason) {
            super(reason);
        }
    }

    private static void unpack(ContentResolver resolver, Uri uri, File base, String name) throws IOException {
        final File temp = new File(base, ".unpacking");
        deleteTree(temp);
        if (!temp.mkdirs()) throw new Refused("the app's folder cannot be written");
        try {
            final String library = unpackInto(resolver, uri, temp);
            // the same name again replaces the earlier one (a driver already loaded keeps its own, now unnamed, file)
            final File target = new File(base, name);
            deleteTree(target);
            if (!temp.renameTo(target)) throw new Refused("the app's folder cannot be written");
            // last: a folder without library.txt is not offered (android.cpp CustomDrivers)
            Files.write(new File(target, "library.txt").toPath(), library.getBytes(StandardCharsets.UTF_8));
        } catch (Refused e) {
            deleteTree(temp);
            throw e;
        } catch (IOException e) {
            deleteTree(temp);
            android.util.Log.w("pt", "driver picker: " + e);
            throw new Refused("it is not a readable zip");
        }
    }

    // the zip's files, flat into `dir` (no path from the zip is followed); returns the driver's .so named in its meta.json
    private static String unpackInto(ContentResolver resolver, Uri uri, File dir) throws IOException {
        long total = 0;
        try (InputStream in = resolver.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in))) {
            final byte[] buffer = new byte[1 << 16];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String file = entry.getName().replace('\\', '/');
                file = file.substring(file.lastIndexOf('/') + 1);
                if (file.isEmpty() || file.startsWith(".")) continue;
                try (OutputStream out = new FileOutputStream(new File(dir, file))) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_UNPACKED) throw new Refused("it is too large for a driver");
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        final File meta = new File(dir, "meta.json");
        if (!meta.isFile()) throw new Refused("it has no meta.json, so it is not an AdrenoTools driver");
        String text = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
        final String library;
        try {
            library = new JSONObject(text).optString("libraryName", "");
        } catch (JSONException e) {
            throw new Refused("its meta.json cannot be read");
        }
        if (library.isEmpty() || library.contains("/") || !new File(dir, library).isFile()) {
            throw new Refused("its driver library (meta.json libraryName) is missing");
        }
        return library;
    }

    private static void deleteTree(File file) {
        final File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
