package com.easemic.app.feature.library;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestImageContentProvider extends ContentProvider {
    private static final String IMAGE_PATH = "/independently-authored-cover.png";
    private static final String SUCCESS_FIRST_PATH = "/success-first.png";
    private static final String SUCCESS_SECOND_PATH = "/success-second.png";
    private static final String MISSING_FIRST_PATH = "/missing-first.png";
    private static final String MISSING_SECOND_PATH = "/missing-second.png";
    private static final String OPEN_COUNT_PREFIX = "/test-open-count/";
    private File imageFile;
    private final AtomicInteger missingFirstOpenCount = new AtomicInteger();
    private final AtomicInteger missingSecondOpenCount = new AtomicInteger();

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Test image provider has no context");
        }
        imageFile = new File(context.getCacheDir(), "independently-authored-cover.png");
        Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.rgb(239, 91, 55));
            try (FileOutputStream output = new FileOutputStream(imageFile)) {
                return bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create test image", exception);
            }
        } finally {
            bitmap.recycle();
        }
    }

    @Override
    public String getType(Uri uri) {
        return isImagePath(uri.getPath()) ? "image/png" : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (!"r".equals(mode)) {
            throw new FileNotFoundException(uri.toString());
        }
        if (MISSING_FIRST_PATH.equals(path)) {
            missingFirstOpenCount.incrementAndGet();
            throw new FileNotFoundException(uri.toString());
        }
        if (MISSING_SECOND_PATH.equals(path)) {
            missingSecondOpenCount.incrementAndGet();
            throw new FileNotFoundException(uri.toString());
        }
        if (!isImagePath(path)) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(imageFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        String path = uri.getPath();
        if (path == null || !path.startsWith(OPEN_COUNT_PREFIX)) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"open_count"});
        String requestedPath = "/" + path.substring(OPEN_COUNT_PREFIX.length());
        int openCount = MISSING_FIRST_PATH.equals(requestedPath)
                ? missingFirstOpenCount.get()
                : MISSING_SECOND_PATH.equals(requestedPath) ? missingSecondOpenCount.get() : 0;
        cursor.addRow(new Object[]{openCount});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private static boolean isImagePath(String path) {
        return IMAGE_PATH.equals(path)
                || SUCCESS_FIRST_PATH.equals(path)
                || SUCCESS_SECOND_PATH.equals(path);
    }
}
