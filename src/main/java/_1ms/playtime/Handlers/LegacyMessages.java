package _1ms.playtime.Handlers;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Existing PlaytimeLink wire format, bounded for legacy Bukkit plugin messages. */
public final class LegacyMessages {
    public static final int MAX_PACKET_BYTES = 32766;
    private static final Gson GSON = new Gson();

    private LegacyMessages() {}

    public record Packet(byte[] bytes, int entries) {}

    public static List<Packet> encode(String type, Map<String, Long> times) {
        List<Packet> packets = new ArrayList<>();
        StringBuilder json = new StringBuilder("{");
        int overhead = 2 + modifiedUtfLength(type) + 2;
        int length = 2; // JSON braces
        int entries = 0;
        for(Map.Entry<String, Long> entry : times.entrySet()) {
            String member = GSON.toJson(entry.getKey()) + ":" + entry.getValue();
            int memberLength = modifiedUtfLength(member);
            if(overhead + 2 + memberLength > MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Player entry exceeds plugin message size limit");
            if(overhead + length + memberLength + (entries == 0 ? 0 : 1) > MAX_PACKET_BYTES) {
                packets.add(packet(type, json.append('}').toString(), entries));
                json = new StringBuilder("{");
                length = 2;
                entries = 0;
            }
            if(entries > 0) {
                json.append(',');
                length++;
            }
            json.append(member);
            length += memberLength;
            entries++;
        }
        packets.add(packet(type, json.append('}').toString(), entries));
        return List.copyOf(packets);
    }

    private static Packet packet(String type, String json, int entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(type);
            out.writeUTF(json);
            return new Packet(bytes.toByteArray(), entries);
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode bounded plugin message", impossible);
        }
    }

    private static int modifiedUtfLength(String value) {
        int bytes = 0;
        for(int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            bytes += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
        }
        return bytes;
    }
}
