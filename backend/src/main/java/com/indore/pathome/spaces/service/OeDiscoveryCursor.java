package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.VisitSession;

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
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Short-lived, authenticated keyset position; session authorization is checked separately on every page. */
final class OeDiscoveryCursor {
    private static final int VERSION = 1;
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final long LIFETIME_SECONDS = 600;
    private static final byte[] DOMAIN = "pathome-oe-ge-discovery-v1".getBytes(StandardCharsets.UTF_8);

    private OeDiscoveryCursor() {}

    static String encode(String secret, Long actorId, VisitSession session, String query, int size,
            long lastScannedId, Instant now) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeLong(actorId);
            out.writeLong(session.getId());
            out.writeLong(session.getVersion());
            out.writeLong(session.getSupportedCity().getId());
            out.writeLong(session.getOperatingTeam().getId());
            out.writeUTF(query);
            out.writeInt(size);
            out.writeLong(lastScannedId);
            out.writeLong(now.getEpochSecond());
            out.flush();
            byte[] payload = bytes.toByteArray();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(secret, payload));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create discovery cursor", ex);
        }
    }

    static long decode(String secret, String cursor, Long actorId, VisitSession session,
            String query, int size, Instant now) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH)
            throw invalid();
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(parts[0])
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1]))
                throw invalid();
            if (!MessageDigest.isEqual(signature, mac(secret, payload))) throw invalid();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            int version = in.readInt();
            long owner = in.readLong();
            long sessionId = in.readLong();
            long sessionVersion = in.readLong();
            long cityId = in.readLong();
            long teamId = in.readLong();
            String originalQuery = in.readUTF();
            int originalSize = in.readInt();
            long lastId = in.readLong();
            long issuedAt = in.readLong();
            if (in.available() != 0 || version != VERSION || owner != actorId
                    || sessionId != session.getId() || sessionVersion != session.getVersion()
                    || cityId != session.getSupportedCity().getId()
                    || teamId != session.getOperatingTeam().getId()
                    || !Objects.equals(originalQuery, query) || originalSize != size || lastId <= 0
                    || issuedAt > now.getEpochSecond() || issuedAt < now.getEpochSecond() - LIFETIME_SECONDS)
                throw invalid();
            return lastId;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static byte[] mac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(DOMAIN);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Discovery cursor signing unavailable", ex);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or expired discovery cursor");
    }
}
