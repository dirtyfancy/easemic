package com.easemic.app.work;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Debug-only provider used to hold a production ScanWorker inside a real
 * ContentResolver boundary while instrumentation inspects foreground state.
 */
public final class ForegroundBlockingContentProvider extends ContentProvider {
    private static volatile CountDownLatch queryEntered = new CountDownLatch(1);
    private static volatile CountDownLatch releaseQuery = new CountDownLatch(1);
    private static volatile CountDownLatch cancellationObserved = new CountDownLatch(1);

    public static void reset() {
        queryEntered = new CountDownLatch(1);
        releaseQuery = new CountDownLatch(1);
        cancellationObserved = new CountDownLatch(1);
    }

    public static boolean awaitQuery(long timeoutMillis) throws InterruptedException {
        return queryEntered.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public static void releaseQuery() {
        releaseQuery.countDown();
    }

    public static boolean awaitCancellation(long timeoutMillis) throws InterruptedException {
        return cancellationObserved.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return queryInternal(projection, null);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            CancellationSignal cancellationSignal
    ) {
        return queryInternal(projection, cancellationSignal);
    }

    private Cursor queryInternal(
            String[] projection,
            CancellationSignal cancellationSignal
    ) {
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(() -> {
                cancellationObserved.countDown();
                releaseQuery.countDown();
            });
        }
        queryEntered.countDown();
        try {
            releaseQuery.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }

        String[] columns = projection != null
                ? projection
                : new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
                row.add("synthetic-archive");
            } else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) {
                row.add("synthetic.cbz");
            } else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) {
                row.add(128L);
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) {
                row.add(1L);
            } else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) {
                row.add(0);
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) {
                row.add("application/zip");
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/zip";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        throw new FileNotFoundException("Debug provider exposes metadata only.");
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
}
