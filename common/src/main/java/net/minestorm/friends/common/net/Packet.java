package net.minestorm.friends.common.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Wire format:  [32 byte HMAC-SHA256][UTF type][byte argc][UTF arg]*[UTF origin]
 *
 * The proxy only relays the raw bytes. Backends verify the HMAC with the shared
 * secret from config.yml, so a client can never forge a packet.
 * Channel name is short + namespaced: valid on 1.8 (<=20 chars) and on 1.13+.
 *
 * FIX: the trailing "origin" field identifies the backend that sent the packet.
 * The sender applies its own packet locally right away and ignores the echo
 * coming back from the proxy, so actions keep working even when the proxy relay
 * is broken. Old packets without the field decode with origin = "".
 */
public final class Packet {
    public static final String CHANNEL = "msfriends:main";
    private static final int MAC_LEN = 32;

    // ── packet types ────────────────────────────────────────────────
    public static final String FRIEND_ADD     = "FADD"; // a, aName, b, bName
    public static final String FRIEND_REMOVE  = "FREM"; // a, b, actorName ("" = silent)
    public static final String FRIEND_CLEAR   = "FCLR"; // owner, actorName ("" = silent)
    public static final String REQUEST_SEND   = "RSND"; // fromUuid, fromName, targetName
    public static final String REQUEST_REG    = "RREG"; // from, fromName, to, toName, createdMillis
    public static final String REQUEST_REMOVE = "RDEL"; // to, from
    public static final String REQUEST_CLEAR  = "RCLR"; // to
    public static final String TOGGLE         = "TOGL"; // uuid, value, name
    public static final String MESSAGE        = "MSG";  // uuid, messageKey, args...
    public static final String JOIN           = "JOIN"; // uuid, name, server
    public static final String QUIT           = "QUIT"; // uuid, name, server
    public static final String HERE           = "HERE"; // uuid, name, server

    private final String type;
    private final String[] args;
    private String origin = "";

    public Packet(String type, String... args) {
        this.type = type;
        this.args = args == null ? new String[0] : args;
    }

    public String type() { return type; }
    public String[] args() { return args; }
    public String arg(int i) { return i >= 0 && i < args.length && args[i] != null ? args[i] : ""; }

    /** Id of the backend instance that sent this packet ("" if unknown / old packet). */
    public String origin() { return origin; }

    /** Copy of this packet tagged with the sending backend's instance id. */
    public Packet withOrigin(String id) {
        Packet copy = new Packet(type, args);
        copy.origin = id == null ? "" : id;
        return copy;
    }

    public byte[] encode(String secret) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.writeUTF(type);
            out.writeByte(args.length);
            for (String a : args) out.writeUTF(a == null ? "" : a);
            out.writeUTF(origin == null ? "" : origin);
            byte[] payload = body.toByteArray();

            ByteArrayOutputStream full = new ByteArrayOutputStream();
            full.write(mac(secret, payload));
            full.write(payload);
            return full.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the packet, or null if malformed / signature mismatch. */
    public static Packet decode(byte[] data, String secret) {
        if (data == null || data.length <= MAC_LEN) return null;
        byte[] mac = Arrays.copyOfRange(data, 0, MAC_LEN);
        byte[] body = Arrays.copyOfRange(data, MAC_LEN, data.length);
        if (!MessageDigest.isEqual(mac, mac(secret, body))) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            String type = in.readUTF();
            int n = in.readUnsignedByte();
            String[] args = new String[n];
            for (int i = 0; i < n; i++) args[i] = in.readUTF();
            Packet p = new Packet(type, args);
            try {
                p.origin = in.readUTF();
            } catch (IOException noOrigin) {
                p.origin = ""; // packet from an older version
            }
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] mac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
