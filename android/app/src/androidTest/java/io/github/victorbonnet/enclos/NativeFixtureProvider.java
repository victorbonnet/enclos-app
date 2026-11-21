package io.github.victorbonnet.enclos;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * TEST APK only. Serves generated fixture bytes from its own private directory, never target-app paths.
 * It is Java because it runs in the test package's own process, which has no Kotlin standard library:
 * it uses only Android framework and java.* classes (the client helpers, which run in the
 * instrumentation process, also use androidx.test).
 */
public class NativeFixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.victorbonnet.enclos.test.nativefixtures";

    private static final Set<String> NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "IMG_1.jpg", "sticker.bin", "moving.gif", "clear.png", "sunrise.jpg", "report.pdf", "example.py")));

    /** Client helper: runs in the instrumentation process. */
    public static Uri serve(String name, byte[] bytes) {
        ContentResolver resolver = InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
        Bundle extras = new Bundle();
        extras.putByteArray("bytes", bytes);
        Bundle result = resolver.call(Uri.parse("content://" + AUTHORITY), "serve", name, extras);
        if (result == null) throw new IllegalStateException("Required value was null.");
        String uri = result.getString("uri");
        if (uri == null) throw new NullPointerException("uri");
        return Uri.parse(uri);
    }

    /** Client helper: runs in the instrumentation process. */
    public static void clear() {
        InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().call(
                Uri.parse("content://" + AUTHORITY), "clear", null, null);
    }

    private File root() {
        Context context = Objects.requireNonNull(getContext(), "context");
        File root = new File(context.getCacheDir(), "native-fixtures");
        root.mkdirs();
        return root;
    }

    /** Deletes a tree without following symbolic links: a link is removed itself, never its target. */
    private static void deleteTree(File f) {
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        f.delete();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("clear".equals(method)) {
            deleteTree(root());
            return new Bundle();
        }
        if (!"serve".equals(method) || !NAMES.contains(arg)) throw new IllegalArgumentException("Failed requirement.");
        byte[] bytes = extras == null ? null : extras.getByteArray("bytes");
        if (bytes == null) throw new IllegalArgumentException("Required value was null.");
        if (bytes.length < 1 || bytes.length > 10_000_000) throw new IllegalArgumentException("Failed requirement.");
        String id = UUID.randomUUID().toString();
        File dir = new File(root(), id);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, arg))) {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Bundle result = new Bundle();
        result.putString("uri", "content://" + AUTHORITY + "/" + id + "/" + arg);
        return result;
    }

    private File file(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || segments.size() != 2) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        String id = segments.get(0);
        String name = segments.get(1);
        if (!UUID.fromString(id).toString().equals(id) || !NAMES.contains(name)) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        File f = new File(new File(root(), id), name);
        if (!f.isFile()) throw new IllegalArgumentException("Failed requirement.");
        return f;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = file(uri);
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {f.getName(), Long.valueOf(f.length())});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = file(uri).getName();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        switch (extension) {
            case "jpg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            default: return "application/octet-stream";
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Failed requirement.");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
