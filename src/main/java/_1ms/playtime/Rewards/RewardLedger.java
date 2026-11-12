package _1ms.playtime.Rewards;

import _1ms.playtime.Handlers.StorageException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Append-only, forced journal. PREPARED after a crash means delivery is uncertain. */
public final class RewardLedger {
    public enum State { PREPARED, DONE, FAILED }
    private static final String HEADER = "VPT-REWARDS-1\n";
    private final Path path;
    private final Map<String, State> states = new HashMap<>();
    private long expectedSize;
    private FileTime expectedModified;
    private boolean healthy = true;

    public RewardLedger(Path path) {
        this.path = path;
        try {
            if(!Files.exists(path)) return;
            byte[] bytes = Files.readAllBytes(path);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if(!text.startsWith(HEADER) || !text.endsWith("\n")) throw new IOException("Incomplete reward journal");
            for(String line : text.substring(HEADER.length()).split("\n")) {
                if(line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if(fields.length != 3 || !fields[0].matches("[a-f0-9]{64}")
                        || !fields[2].equals(hash(fields[0] + "\t" + fields[1])))
                    throw new IOException("Invalid reward journal entry");
                states.put(fields[0], State.valueOf(fields[1]));
            }
            expectedSize = bytes.length;
            expectedModified = Files.getLastModifiedTime(path);
        } catch(IOException | IllegalArgumentException failure) {
            throw new StorageException("Cannot read reward journal; rewards disabled without replacing the original", failure);
        }
    }

    public static String id(String player, long threshold, String command) {
        return hash(player.toLowerCase(Locale.ROOT) + "\n" + threshold + "\n" + command);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public synchronized State get(String id) { return states.get(id); }

    public synchronized void put(String id, State state) {
        if(!healthy) throw new StorageException("Reward journal failed; restart after checking its original file", null);
        if(!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid reward identity");
        try {
            boolean exists = Files.exists(path);
            if(exists != (expectedModified != null) || (exists && (Files.size(path) != expectedSize
                    || !Files.getLastModifiedTime(path).equals(expectedModified))))
                throw new IOException("Reward journal changed outside the plugin");
            String record = id + "\t" + state;
            byte[] bytes = ((exists ? "" : HEADER) + record + "\t" + hash(record) + "\n").getBytes(StandardCharsets.UTF_8);
            try(FileChannel channel = FileChannel.open(path, exists
                    ? new StandardOpenOption[]{StandardOpenOption.WRITE}
                    : new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW});
                var lock = channel.tryLock()) {
                if(lock == null || channel.size() != expectedSize) throw new IOException("Reward journal is in use");
                channel.position(expectedSize);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            expectedSize += bytes.length;
            expectedModified = Files.getLastModifiedTime(path);
            states.put(id, state);
        } catch(IOException | RuntimeException failure) {
            healthy = false;
            throw new StorageException("Cannot persist reward state; no further rewards will be dispatched", failure);
        }
    }
}
