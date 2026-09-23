package com.magilhub.printnats.android.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.JobStatus;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.spi.InboundStore;
import com.magilhub.printnats.spi.JobStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain SQLite (no Room — host apps pin different Room versions) for the SDK's own database
 * {@code print_nats_sdk.db}; never touches the host's {@code printer_database}.
 * Every schema change must add a real migration in {@link Helper#onUpgrade} — never drop tables.
 */
public final class SqliteStores extends SQLiteOpenHelper {
    public static final String DB_NAME = "print_nats_sdk.db";
    private static final int VERSION = 2;

    public SqliteStores(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs (job_id TEXT PRIMARY KEY, kind TEXT, printer_id TEXT, payload TEXT, status TEXT,"
                + " retries INTEGER, reason TEXT, category TEXT, created_at INTEGER, updated_at INTEGER, order_id TEXT,"
                + " order_no TEXT, sort_order TEXT, kot_no TEXT, message_id TEXT, source TEXT, is_station INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX jobs_status ON jobs(status)");
        db.execSQL("CREATE TABLE inbound (key TEXT PRIMARY KEY, message_type TEXT, message_data TEXT, message_id TEXT,"
                + " received_at INTEGER, done INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // One migration per version; never drop data.
        if (oldVersion < 2) db.execSQL("ALTER TABLE jobs ADD COLUMN is_station INTEGER NOT NULL DEFAULT 0");
    }

    public JobStore jobStore() {
        return new Jobs();
    }

    public InboundStore inboundStore() {
        return new Inbound();
    }

    // ---- jobs ------------------------------------------------------------------------------------------

    private final class Jobs implements JobStore {
        @Override
        public void insert(PrintJob job) {
            long row = getWritableDatabase().insertWithOnConflict("jobs", null, values(job), SQLiteDatabase.CONFLICT_ABORT);
            if (row < 0) throw new IllegalStateException("duplicate jobId " + job.jobId);
        }

        @Override
        public void update(PrintJob job) {
            getWritableDatabase().insertWithOnConflict("jobs", null, values(job), SQLiteDatabase.CONFLICT_REPLACE);
        }

        @Override
        public PrintJob get(String jobId) {
            try (Cursor c = getReadableDatabase().query("jobs", null, "job_id=?", new String[]{jobId}, null, null, null)) {
                return c.moveToFirst() ? read(c) : null;
            }
        }

        @Override
        public boolean compareAndSetStatus(String jobId, JobStatus expected, JobStatus next) {
            ContentValues v = new ContentValues();
            v.put("status", next.name());
            v.put("updated_at", System.currentTimeMillis());
            return getWritableDatabase().update("jobs", v, "job_id=? AND status=?", new String[]{jobId, expected.name()}) == 1;
        }

        @Override
        public List<PrintJob> findByStatus(JobStatus... statuses) {
            StringBuilder where = new StringBuilder("status IN (");
            String[] args = new String[statuses.length];
            for (int i = 0; i < statuses.length; i++) {
                where.append(i == 0 ? "?" : ",?");
                args[i] = statuses[i].name();
            }
            where.append(')');
            List<PrintJob> out = new ArrayList<>();
            try (Cursor c = getReadableDatabase().query("jobs", null, where.toString(), args, null, null, "created_at")) {
                while (c.moveToNext()) out.add(read(c));
            }
            return out;
        }

        @Override
        public void delete(String jobId) {
            getWritableDatabase().delete("jobs", "job_id=?", new String[]{jobId});
        }

        private ContentValues values(PrintJob j) {
            ContentValues v = new ContentValues();
            v.put("job_id", j.jobId);
            v.put("kind", j.kind.name());
            v.put("printer_id", j.printerId);
            v.put("payload", j.payloadJson);
            v.put("status", j.status.name());
            v.put("retries", j.retries);
            v.put("reason", j.reason);
            v.put("category", j.category);
            v.put("created_at", j.createdAt);
            v.put("updated_at", j.updatedAt);
            v.put("order_id", j.orderId);
            v.put("order_no", j.orderNo);
            v.put("sort_order", j.sortOrder);
            v.put("kot_no", j.kotNo);
            v.put("message_id", j.messageId);
            v.put("source", j.source);
            v.put("is_station", j.isStation ? 1 : 0);
            return v;
        }

        private PrintJob read(Cursor c) {
            PrintJob j = new PrintJob();
            j.jobId = c.getString(c.getColumnIndexOrThrow("job_id"));
            j.kind = JobKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind")));
            j.printerId = c.getString(c.getColumnIndexOrThrow("printer_id"));
            j.payloadJson = c.getString(c.getColumnIndexOrThrow("payload"));
            j.status = JobStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status")));
            j.retries = c.getInt(c.getColumnIndexOrThrow("retries"));
            j.reason = c.getString(c.getColumnIndexOrThrow("reason"));
            j.category = c.getString(c.getColumnIndexOrThrow("category"));
            j.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
            j.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
            j.orderId = c.getString(c.getColumnIndexOrThrow("order_id"));
            j.orderNo = c.getString(c.getColumnIndexOrThrow("order_no"));
            j.sortOrder = c.getString(c.getColumnIndexOrThrow("sort_order"));
            j.kotNo = c.getString(c.getColumnIndexOrThrow("kot_no"));
            j.messageId = c.getString(c.getColumnIndexOrThrow("message_id"));
            j.source = c.getString(c.getColumnIndexOrThrow("source"));
            j.isStation = c.getInt(c.getColumnIndexOrThrow("is_station")) == 1;
            return j;
        }
    }

    // ---- inbound ---------------------------------------------------------------------------------------

    private final class Inbound implements InboundStore {
        @Override
        public boolean record(InboundStore.Inbound in) {
            ContentValues v = new ContentValues();
            v.put("key", in.key);
            v.put("message_type", in.messageType);
            v.put("message_data", in.messageData);
            v.put("message_id", in.messageId);
            v.put("received_at", in.receivedAt);
            return getWritableDatabase().insertWithOnConflict("inbound", null, v, SQLiteDatabase.CONFLICT_IGNORE) >= 0;
        }

        @Override
        public void markDone(String key) {
            ContentValues v = new ContentValues();
            v.put("done", 1);
            getWritableDatabase().update("inbound", v, "key=?", new String[]{key});
        }

        @Override
        public List<InboundStore.Inbound> pending() {
            List<InboundStore.Inbound> out = new ArrayList<>();
            try (Cursor c = getReadableDatabase().query("inbound", null, "done=0", null, null, null, "received_at")) {
                while (c.moveToNext()) {
                    out.add(new InboundStore.Inbound(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)));
                }
            }
            return out;
        }

        @Override
        public void prune(long cutoffMillis) {
            getWritableDatabase().delete("inbound", "done=1 AND received_at<?", new String[]{String.valueOf(cutoffMillis)});
        }
    }
}
