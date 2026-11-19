/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 固定版本的本地 Binder 合同。脱敏导出和受限预览使用各自的操作及票据。 */
final class IslandCaptureIpc {
    static final String DESCRIPTOR = "com.hyperos3.focusrestore.capture.v1";
    static final int STATUS = IBinder.FIRST_CALL_TRANSACTION;
    static final int START = STATUS + 1;
    static final int END = STATUS + 2;
    static final int PREPARE = STATUS + 3;
    static final int CHUNK = STATUS + 4;
    static final int CHECK = STATUS + 5;
    static final int RELEASE = STATUS + 6;
    static final int PREVIEW_LIST = STATUS + 7;
    static final int OPEN_PREVIEW = STATUS + 8;
    static final int READ_PREVIEW_PAGE = STATUS + 9;
    static final int CLOSE_PREVIEW = STATUS + 10;
    static final int VISIBLE_SNIPPETS = STATUS + 11;
    static final int CHECK_PREVIEW = STATUS + 12;
    static final int ISSUE_SAVE = STATUS + 13;
    static final int CONSUME_SAVE = STATUS + 14;
    static final int MAX_SNIPPET_REPLY_BYTES = 2048;

    static List<String> visibleSnippets(IslandCaptureProvider.BridgeRegistration registration, long generation,
            long[] handles) throws IOException {
        if (handles.length == 0 || handles.length > IslandCaptureEditor.MAX_VISIBLE_BATCH) {
            throw new IOException("CAPTURE_SNIPPET_REQUEST_INVALID");
        }
        return callPreview(registration, VISIBLE_SNIPPETS, p -> {
            p.writeLong(generation);
            p.writeInt(handles.length);
            for (long handle : handles) p.writeLong(handle);
        }, p -> {
            if (p.dataSize() > MAX_SNIPPET_REPLY_BYTES || p.readInt() != handles.length) {
                throw new IOException("CAPTURE_SNIPPET_REPLY_INVALID");
            }
            List<String> result = new ArrayList<>(handles.length);
            for (int i = 0; i < handles.length; i++) {
                String text = p.readString();
                if (text == null || text.length() > IslandCaptureEditor.MAX_SNIPPET_UNITS) {
                    throw new IOException("CAPTURE_SNIPPET_REPLY_INVALID");
                }
                result.add(text);
            }
            if (p.dataAvail() != 0) throw new IOException("CAPTURE_SNIPPET_REPLY_INVALID");
            return result;
        });
    }

    static void checkPreview(PreviewTicket ticket) throws IOException {
        callPreview(ticket.registration, CHECK_PREVIEW, ticket::write, p -> {
            if (p.dataAvail() != 0) throw new IOException("CAPTURE_PREVIEW_REPLY_INVALID");
            return null;
        });
    }

    static long issueSave(PreviewTicket ticket, long baseline, IslandCaptureEditor.Candidate candidate,
            long createdAt) throws IOException {
        return callPreview(ticket.registration, ISSUE_SAVE, p -> {
            ticket.write(p);
            p.writeLong(baseline);
            p.writeString(candidate.digest);
            p.writeLong(createdAt);
            p.writeInt(candidate.paths.size());
            for (IslandRulePaths.Entry path : candidate.paths) p.writeInt(IslandRulePaths.all().indexOf(path));
        }, p -> {
            long id = p.readLong();
            if (id <= 0 || p.dataAvail() != 0) throw new IOException("CAPTURE_SAVE_UNAVAILABLE");
            return id;
        });
    }

    static void consumeSave(PreviewTicket ticket, long id, long baseline, String digest) throws IOException {
        callPreview(ticket.registration, CONSUME_SAVE, p -> {
            p.writeLong(ticket.generation);
            p.writeLong(ticket.handle);
            p.writeLong(id);
            p.writeLong(baseline);
            p.writeString(digest);
        }, p -> {
            if (p.dataAvail() != 0) throw new IOException("CAPTURE_SAVE_REPLY_INVALID");
            return null;
        });
    }
    static final int MAX_PREVIEW_LIST_BYTES = 20 * 1024;

    static final class PreviewTicket {
        private final IslandCaptureProvider.BridgeRegistration registration;
        private final long generation;
        private final long handle;
        private final long id;

        private PreviewTicket(IslandCaptureProvider.BridgeRegistration registration, long generation, long handle, long id) {
            this.registration = registration;
            this.generation = generation;
            this.handle = handle;
            this.id = id;
        }

        boolean matches(IslandCaptureProvider.BridgeRegistration expected, long generation, long handle) {
            return registration == expected && this.generation == generation && this.handle == handle;
        }

        private void write(Parcel out) {
            out.writeLong(generation);
            out.writeLong(handle);
            out.writeLong(id);
        }
    }

    private static <T> T callPreview(IslandCaptureProvider.BridgeRegistration registration, int code,
            Write write, Read<T> read) throws IOException {
        if (registration == null || IslandCaptureProvider.currentRegistration(registration.bridge) != registration) {
            throw new IOException("CAPTURE_PREVIEW_CONNECTION_CHANGED");
        }
        T result = call(registration.bridge, code, write, read);
        if (IslandCaptureProvider.currentRegistration(registration.bridge) != registration) {
            throw new IOException("CAPTURE_PREVIEW_CONNECTION_CHANGED");
        }
        return result;
    }

    static List<IslandCapturePreview.Summary> previewList(IBinder bridge, long generation) throws IOException {
        return previewList(IslandCaptureProvider.currentRegistration(bridge), generation);
    }

    static List<IslandCapturePreview.Summary> previewList(IslandCaptureProvider.BridgeRegistration registration,
            long generation) throws IOException {
        return callPreview(registration, PREVIEW_LIST, p -> p.writeLong(generation), p -> {
            int count = p.readInt();
            if (count < 0 || count > IslandCapturePreview.MAX_RECORDS || p.dataSize() > MAX_PREVIEW_LIST_BYTES) {
                throw new IOException("CAPTURE_PREVIEW_LIST_INVALID");
            }
            List<IslandCapturePreview.Summary> summaries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long handle = p.readLong();
                String pkg = p.readString();
                long capturedAt = p.readLong();
                String sourceKey = p.readString();
                int fields = p.readInt();
                int reason = p.readInt();
                IslandCaptureInputAdapter.Uneditable[] reasons = IslandCaptureInputAdapter.Uneditable.values();
                if (handle <= 0 || (i > 0 && summaries.get(i - 1).handle <= handle) || !IslandCaptureRuntime.validPackage(pkg)
                        || fields < 0 || fields > IslandCapturePreview.MAX_FIELDS || reason < -1 || reason >= reasons.length
                        || (fields == 0) != (reason >= 0) || (sourceKey != null && !IslandCapturePreview.isSourceKey(sourceKey))
                        || (fields > 0 && sourceKey == null)) {
                    throw new IOException("CAPTURE_PREVIEW_LIST_INVALID");
                }
                summaries.add(new IslandCapturePreview.Summary(handle, pkg, capturedAt, sourceKey, fields,
                        reason < 0 ? null : reasons[reason]));
            }
            if (p.dataAvail() != 0) throw new IOException("CAPTURE_PREVIEW_LIST_INVALID");
            return Collections.unmodifiableList(summaries);
        });
    }

    static PreviewTicket openPreview(IBinder bridge, long generation, long handle) throws IOException {
        return openPreview(IslandCaptureProvider.currentRegistration(bridge), generation, handle);
    }

    static PreviewTicket openPreview(IslandCaptureProvider.BridgeRegistration registration, long generation,
            long handle) throws IOException {
        return callPreview(registration, OPEN_PREVIEW, p -> { p.writeLong(generation); p.writeLong(handle); }, p -> {
            long id = p.readLong();
            if (id >= 0 || p.dataAvail() != 0) throw new IOException("CAPTURE_PREVIEW_UNAVAILABLE");
            return new PreviewTicket(registration, generation, handle, id);
        });
    }

    static IslandCapturePreview.Page readPreviewPage(PreviewTicket ticket, int offset) throws IOException {
        if (offset < 0 || offset >= IslandCapturePreview.MAX_FIELDS) throw new IOException("CAPTURE_PREVIEW_OFFSET_INVALID");
        return callPreview(ticket.registration, READ_PREVIEW_PAGE, p -> { ticket.write(p); p.writeInt(offset); }, p -> {
            int next = p.readInt();
            int count = p.readInt();
            if (count <= 0 || count > IslandCapturePreview.MAX_PAGE_FIELDS || offset + count > IslandCapturePreview.MAX_FIELDS
                    || (next != -1 && (next != offset + count || next >= IslandCapturePreview.MAX_FIELDS))
                    || p.dataSize() > IslandCapturePreview.MAX_PAGE_BYTES) throw new IOException("CAPTURE_PREVIEW_PAGE_INVALID");
            List<IslandCapturePreview.Field> fields = new ArrayList<>(count);
            int bytes = IslandCapturePreview.PAGE_HEADER_BYTES;
            int previousIndex = -1;
            for (int i = 0; i < count; i++) {
                int index = p.readInt();
                int tag = p.readInt();
                if (index <= previousIndex || index >= IslandCapturePreview.MAX_FIELDS) {
                    throw new IOException("CAPTURE_PREVIEW_PATH_INVALID");
                }
                previousIndex = index;
                IslandRulePaths.Entry path = IslandRulePaths.all().get(index);
                IslandCapturePreview.Field field;
                switch (tag) {
                    case 1: field = IslandCapturePreview.Field.create(path, IslandCapturePreview.Type.STRING, p.readString(), null); break;
                    case 2:
                        int value = p.readInt();
                        if (value != 0 && value != 1) throw new IOException("CAPTURE_PREVIEW_VALUE_INVALID");
                        field = IslandCapturePreview.Field.create(path, IslandCapturePreview.Type.BOOLEAN, null, value == 1);
                        break;
                    case 3: field = IslandCapturePreview.Field.create(path, IslandCapturePreview.Type.NUMBER, p.readString(), null); break;
                    default: throw new IOException("CAPTURE_PREVIEW_TYPE_INVALID");
                }
                if (field == null || field.wireBytes > IslandCapturePreview.MAX_PAGE_BYTES - bytes) {
                    throw new IOException("CAPTURE_PREVIEW_VALUE_INVALID");
                }
                bytes += field.wireBytes;
                fields.add(field);
            }
            if (p.dataAvail() != 0) throw new IOException("CAPTURE_PREVIEW_PAGE_INVALID");
            return new IslandCapturePreview.Page(next, fields);
        });
    }

    static void writePreviewPage(Parcel out, IslandCapturePreview.Page page) {
        out.writeInt(page.nextOffset);
        out.writeInt(page.fields.size());
        for (IslandCapturePreview.Field field : page.fields) {
            out.writeInt(IslandRulePaths.all().indexOf(field.path));
            switch (field.type) {
                case STRING: out.writeInt(1); out.writeString(field.text); break;
                case BOOLEAN: out.writeInt(2); out.writeInt(field.booleanValue ? 1 : 0); break;
                case NUMBER: out.writeInt(3); out.writeString(field.text); break;
            }
        }
    }

    static void closePreview(PreviewTicket ticket) throws IOException {
        callPreview(ticket.registration, CLOSE_PREVIEW, ticket::write, p -> null);
    }

    static final class Ticket {
        final long generation;
        final long sample;
        final long id;
        final int size;
        Ticket(long generation, long sample, long id, int size) {
            this.generation = generation;
            this.sample = sample;
            this.id = id;
            this.size = size;
        }
        void write(Parcel out) {
            out.writeLong(generation);
            out.writeLong(sample);
            out.writeLong(id);
        }
    }

    interface Write { void accept(Parcel parcel); }
    interface Read<T> { T read(Parcel parcel) throws IOException; }

    private static <T> T call(IBinder bridge, int code, Write write, Read<T> read) throws IOException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            write.accept(request);
            if (!bridge.transact(code, request, reply, 0)) throw new IOException("CAPTURE_IPC_UNSUPPORTED");
            reply.readException();
            return read.read(reply);
        } catch (RemoteException | RuntimeException failure) {
            throw new IOException("CAPTURE_IPC_UNAVAILABLE", failure);
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    static IslandCaptureRuntime.Status status(IBinder bridge) throws IOException {
        return call(bridge, STATUS, p -> { }, p -> {
            boolean ready = p.readInt() == 1;
            long epoch = p.readLong();
            long generation = p.readLong();
            String target = p.readString();
            int count = p.readInt();
            if (epoch < 0 || count < 0 || count > IslandCapturePreview.MAX_RECORDS || generation < 0
                    || (generation == 0 && (count != 0 || target != null))
                    || (generation != 0 && (generation != epoch || !ready || target != null))) {
                throw new IOException("CAPTURE_STATUS_INVALID");
            }
            List<IslandCaptureRuntime.Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) samples.add(new IslandCaptureRuntime.Sample(p.readLong(), p.readLong()));
            if (p.dataAvail() == 0) return new IslandCaptureRuntime.Status(ready, epoch, generation, target, samples);
            if (p.dataAvail() != 2 * Integer.BYTES) throw new IOException("CAPTURE_STATUS_INVALID");
            int mode = p.readInt();
            int reason = p.readInt();
            IslandCaptureRuntime.Availability[] values = IslandCaptureRuntime.Availability.values();
            if ((mode != 3 && mode != 4) || reason < 0 || reason >= values.length
                    || (values[reason] == IslandCaptureRuntime.Availability.READY) != ready) {
                throw new IOException("CAPTURE_STATUS_INVALID");
            }
            return new IslandCaptureRuntime.Status(mode, values[reason], epoch, generation, target, samples);
        });
    }

    static long start(IBinder bridge, long epoch) throws IOException {
        return call(bridge, START, p -> p.writeLong(epoch), Parcel::readLong);
    }

    static void end(IBinder bridge, long generation) throws IOException {
        call(bridge, END, p -> p.writeLong(generation), p -> null);
    }

    static Ticket prepare(IBinder bridge, long generation, long sample) throws IOException {
        return call(bridge, PREPARE, p -> { p.writeLong(generation); p.writeLong(sample); }, p -> {
            long id = p.readLong();
            int size = p.readInt();
            if (id <= 0 || size <= 0 || size > IslandExportGenerator.MAX_JSON_BYTES) {
                throw new IOException("CAPTURE_TICKET_INVALID");
            }
            return new Ticket(generation, sample, id, size);
        });
    }

    static boolean check(IBinder bridge, Ticket ticket) throws IOException {
        return call(bridge, CHECK, ticket::write, p -> p.readInt() == 1);
    }

    static byte[] chunk(IBinder bridge, Ticket ticket, int offset) throws IOException {
        return call(bridge, CHUNK, p -> { ticket.write(p); p.writeInt(offset); }, p -> {
            byte[] bytes = p.createByteArray();
            if (bytes == null || bytes.length != Math.min(IslandCaptureRuntime.CHUNK_BYTES, ticket.size - offset)) {
                throw new IOException("CAPTURE_CHUNK_INVALID");
            }
            return bytes;
        });
    }

    static void release(IBinder bridge, Ticket ticket) throws IOException {
        call(bridge, RELEASE, ticket::write, p -> null);
    }
}
