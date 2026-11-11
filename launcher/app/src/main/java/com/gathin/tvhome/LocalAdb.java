package com.gathin.tvhome;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

/**
 * Runs one command as this TV's own shell user, through the TV's network debugging, on this TV only (127.0.0.1:5555):
 * what Keys uses to start its key helper. Speaks the debugging protocol directly (a handful of messages). Glass signs
 * in with its own key, kept in the TV's key store (the private key never leaves it); the first time, the TV asks
 * "Allow USB debugging?" for that key (glass@tv), and the person answers it on the TV: Glass never answers it.
 */
final class LocalAdb {
    private LocalAdb() {
    }

    static final String ALIAS = "glass-adb";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    private static final int TOKEN = 1, SIGNATURE = 2, RSAPUBLICKEY = 3;
    /** PKCS#1 DigestInfo for SHA-1: the debugging service treats its 20-byte token as a SHA-1 digest. */
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Asking {
        /** The TV is asking whether to allow this key (its fingerprint, as the TV shows it). */
        void asking(String fingerprint);
    }

    /** The command's output (up to 16 KB), or an exception: debugging off, not allowed, or no answer in time. */
    static String shell(String command, long allowWaitMs, Asking asking) throws Exception {
        return shell(command, allowWaitMs, asking, true);
    }

    /** As shell, only where the TV already allows Glass's key: it never puts the question on the TV (at a start). */
    static String quiet(String command) throws Exception {
        return shell(command, 0, null, false);
    }

    private static String shell(String command, long allowWaitMs, Asking asking, boolean offer) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, offered = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != TOKEN) continue;
                if (!signed) {
                    signed = true;
                    send(out, AUTH, SIGNATURE, 0, sign(m.data));
                } else if (!offered && offer) {
                    offered = true;
                    String pub = publicKey();
                    if (asking != null) asking.asking(fingerprint(pub));
                    s.setSoTimeout((int) allowWaitMs);
                    send(out, AUTH, RSAPUBLICKEY, 0, (pub + "\0").getBytes(StandardCharsets.UTF_8));
                } else {
                    throw new IOException("debugging not allowed for Glass");
                }
            }
            s.setSoTimeout(8000);
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m;
                try {
                    m = read(in);
                } catch (IOException closed) {
                    break;
                }
                if (m.cmd == OKAY) {
                    remote = m.arg0;
                } else if (m.cmd == WRTE) {
                    remote = m.arg0;
                    if (result.size() < 16 * 1024) result.write(m.data);
                    send(out, OKAY, 1, remote, new byte[0]);
                } else if (m.cmd == CLSE) {
                    if (remote != 0) send(out, CLSE, 1, remote, new byte[0]);
                    break;
                }
            }
            return result.toString("UTF-8");
        }
    }

    // ---- the wire ----------------------------------------------------------------------------------------------------

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad message");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- Glass's key ---------------------------------------------------------------------------------------------

    private static KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            g.generateKeyPair();
        }
        return ks;
    }

    /** The token, signed the way the debugging service checks it: PKCS#1 v1.5 over SHA-1's DigestInfo and the token. */
    private static byte[] sign(byte[] token) throws Exception {
        PrivateKey key = (PrivateKey) store().getKey(ALIAS, null);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(SHA1_INFO);
        s.update(token);
        return s.sign();
    }

    /** The public key in the debugging service's own format (base64 of len, n0inv, n, rr, e; little-endian words). */
    static String publicKey() throws Exception {
        RSAPublicKey k = (RSAPublicKey) store().getCertificate(ALIAS).getPublicKey();
        BigInteger n = k.getModulus();
        int words = 64;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32).pow(2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP) + " glass@tv";
    }

    /** As the TV shows it when it asks: MD5 of the key, upper-case hex pairs joined by colons. */
    static String fingerprint(String pub) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(Base64.decode(pub.split("\\s+")[0], Base64.DEFAULT));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02X", d[i] & 0xff));
        }
        return sb.toString();
    }
}
