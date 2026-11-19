package com.quiver.storage.wal;

import com.quiver.core.fsm.FsmDefinition;
import com.quiver.core.instance.FsmInstance;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A durability log: one JSON object per line, fsync'd before the call that produced it
 * is allowed to return. Two record shapes are written, distinguished by a {@code "type"}
 * field: {@code DEFINE_FSM} (one per {@link com.quiver.storage.StorageEngine#defineFsm})
 * and {@code COMMIT} (one per successful transaction, carrying every instance it wrote --
 * whole-instance snapshots rather than deltas, which makes replay a simple last-write-wins
 * overwrite instead of something that has to reconstruct history incrementally).
 *
 * <p><b>Crash safety.</b> Because each record is written and fsync'd as a single
 * appended line before the writer moves on, the only way a crash can corrupt the file is
 * by tearing the very last line (e.g. power loss mid-write). {@link #replay} accounts for
 * this: a parse failure on the last line is treated as an incomplete write and silently
 * dropped; a parse failure on any earlier line is treated as real corruption and thrown.
 *
 * <p>This is a single flat file with no rotation or compaction -- on a long-lived, busy
 * database it would grow without bound. Segmenting and compacting it (e.g. periodically
 * snapshotting live state and truncating the log behind that snapshot) is exactly the
 * kind of thing this increment intentionally leaves for later; see the roadmap.
 */
public final class WriteAheadLog implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path path;
    private final FileOutputStream out;

    private WriteAheadLog(Path path, FileOutputStream out) {
        this.path = path;
        this.out = out;
    }

    public static WriteAheadLog open(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            FileOutputStream out = new FileOutputStream(path.toFile(), /* append = */ true);
            return new WriteAheadLog(path, out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open WAL at " + path, e);
        }
    }

    public synchronized void appendDefineFsm(FsmDefinition def) {
        appendLine(WalCodec.encodeDefinition(def));
    }

    public synchronized void appendCommit(List<FsmInstance> writes) {
        List<Object> encoded = new ArrayList<>();
        for (FsmInstance i : writes) encoded.add(WalCodec.encodeInstance(i));
        appendLine(Map.of("type", "COMMIT", "writes", encoded));
    }

    private void appendLine(Object record) {
        try {
            String json = JSON.writeValueAsString(record);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
            out.getFD().sync();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize WAL record", e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append to WAL at " + path, e);
        }
    }

    /**
     * Replays every complete record in file order, invoking {@code onDefine} for each
     * {@code DEFINE_FSM} record and {@code onCommit} for each {@code COMMIT} record's
     * list of written instances. Does nothing if the log doesn't exist yet (a brand-new
     * database).
     */
    @SuppressWarnings("unchecked")
    public static void replay(Path path, Consumer<FsmDefinition> onDefine, Consumer<List<FsmInstance>> onCommit) {
        if (!Files.exists(path)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read WAL at " + path, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            Map<String, Object> record;
            try {
                record = JSON.readValue(line, Map.class);
            } catch (JsonProcessingException e) {
                if (i == lines.size() - 1) {
                    // Last line, and it doesn't parse: almost certainly a torn write from a
                    // crash mid-append. Drop it and stop -- everything before it is intact.
                    return;
                }
                throw new IllegalStateException(
                        "WAL at %s is corrupt at line %d (not the last line, so this is not an expected torn write)"
                                .formatted(path, i + 1), e);
            }
            String type = (String) record.get("type");
            switch (type) {
                case "DEFINE_FSM" -> onDefine.accept(WalCodec.decodeDefinition(record));
                case "COMMIT" -> {
                    List<Object> writes = (List<Object>) record.get("writes");
                    List<FsmInstance> instances = new ArrayList<>();
                    for (Object w : writes) {
                        instances.add(WalCodec.decodeInstance((Map<String, Object>) w));
                    }
                    onCommit.accept(instances);
                }
                default -> throw new IllegalStateException("WAL at %s: unknown record type '%s' at line %d"
                        .formatted(path, type, i + 1));
            }
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
