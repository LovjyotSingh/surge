package dev.surge.notification.audit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends one JSON line per message to {@code orders-<yyyy-MM-dd>.jsonl}, where the date is the Asia/Kolkata date of
 * the clock's instant (so a file rolls over at IST midnight). Appends and tail reads share one lock, so lines never
 * interleave and a reader never sees a half-written line. If an append fails half way, the
 * fragment is truncated away before the exception propagates, so the retry never merges two lines. (The one
 * remaining gap is in AuditRecorder: a failed commit after a good append can duplicate a line.)
 */
@Component
public class AuditLogWriter {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Path dir;
    private final Clock clock;
    private final JsonMapper json;
    private final ReentrantLock lock = new ReentrantLock();
    /** Parses decimals as BigDecimal so money such as 1500.00 is written back exactly as it was sent. */
    private final ObjectReader reader;

    public AuditLogWriter(@Value("${surge.notification.audit-dir:./audit}") String dir, Clock clock, JsonMapper json) {
        this.dir = Path.of(dir);
        this.clock = clock;
        this.json = json;
        this.reader = json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** The last lines of a day (oldest first) and how many lines that day has in total. */
    public record Tail(long lines, List<String> tail) {
    }

    /**
     * Appends {@code {"at":<instant>,"type":...,"payload":<json>}}. The payload is parsed first (so garbage never
     * reaches the file) and written back compactly as a JSON object.
     *
     * @throws tools.jackson.core.JacksonException when {@code payloadJson} is not valid JSON
     * @throws IllegalArgumentException when it is valid JSON but not an object
     */
    public void append(String type, String payloadJson) {
        JsonNode payload = parse(payloadJson);
        if (!payload.isObject()) {
            throw new IllegalArgumentException("audit payload must be a JSON object");
        }
        Instant now = clock.instant();
        ObjectNode line = json.createObjectNode();
        line.put("at", now.toString());
        line.put("type", type);
        line.set("payload", payload);
        String text = json.writeValueAsString(line) + "\n";
        Path file = fileFor(now.atZone(IST).toLocalDate());
        lock.lock();
        try {
            Files.createDirectories(dir);
            long sizeBefore = Files.exists(file) ? Files.size(file) : 0;
            try {
                write(file, text);
            } catch (IOException | RuntimeException e) {
                truncateTo(file, sizeBefore, e);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to " + file, e);
        } finally {
            lock.unlock();
        }
    }

    /** The actual append; a seam so a test can make it fail half way. */
    void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Cuts a half-written fragment off, so the retry never appends after it and merges two lines. */
    private static void truncateTo(Path file, long size, Exception cause) {
        if (!Files.exists(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            if (channel.size() > size) {
                channel.truncate(size);
            }
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }

    /** Parses with decimals kept as BigDecimal, so a money value is written back exactly as it was sent. */
    public JsonNode parse(String text) {
        return reader.readValue(text);
    }

    /** Today's date in Asia/Kolkata. */
    public LocalDate today() {
        return clock.instant().atZone(IST).toLocalDate();
    }

    /** Built only from a parsed date, never from user text, so no path can escape the audit directory. */
    Path fileFor(LocalDate date) {
        return dir.resolve("orders-" + date + ".jsonl");
    }

    public Tail tail(LocalDate date, int count) {
        Path file = fileFor(date);
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return new Tail(0, List.of());
            }
            Deque<String> last = new ArrayDeque<>();
            long total = 0;
            // Malformed bytes decode to U+FFFD instead of throwing, so a damaged file still tails (never a 500).
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    total++;
                    last.addLast(line);
                    if (last.size() > count) {
                        last.removeFirst();
                    }
                }
            }
            return new Tail(total, new ArrayList<>(last));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        } finally {
            lock.unlock();
        }
    }
}
