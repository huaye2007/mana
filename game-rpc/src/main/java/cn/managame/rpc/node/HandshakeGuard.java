package cn.managame.rpc.node;

import cn.managame.rpc.message.RpcHandshake;
import cn.managame.rpc.netty.RpcWire;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Handshake authentication: HMAC proof with the shared secret, timestamp window, initiator nonce replay
 * protection and the reply's echoed nonce. Without a secret every proof field is zero.
 */
final class HandshakeGuard {
    private static final byte[] ZERO_NONCE = new byte[16];
    /** Above this many remembered nonces, expired ones are purged on the next accepted handshake. */
    private static final int PURGE_THRESHOLD = 1024;

    private final byte[] secret;
    private final long clockSkewMillis;
    private final SecureRandom random = new SecureRandom();
    // Accepted initiator nonces within the clock-skew window; rejects replayed handshakes.
    private final ConcurrentHashMap<ByteBuffer, Long> seenNonces = new ConcurrentHashMap<>();
    private volatile long nextPurgeNanos;

    HandshakeGuard(byte[] secret, long clockSkewMillis) {
        this.secret = secret == null ? null : secret.clone();
        this.clockSkewMillis = clockSkewMillis;
        nextPurgeNanos = System.nanoTime();
    }

    /** Initiating handshake; returns the frame and stores the nonce the reply must echo in {@code context}. */
    ByteBuf initiate(RpcConnectionContext context, RpcHandshake identity) {
        byte[] nonce = newNonce();
        context.sentNonce = nonce;
        return RpcWire.encodeHandshake(identity,
                new RpcWire.HandshakeProof(System.currentTimeMillis(), nonce, ZERO_NONCE), secret);
    }

    /** Reply to an accepted initiating handshake, echoing its nonce. */
    ByteBuf reply(RpcHandshake identity, RpcWire.HandshakeProof initiator) {
        return RpcWire.encodeHandshake(identity,
                new RpcWire.HandshakeProof(System.currentTimeMillis(), newNonce(), initiator.nonce()), secret);
    }

    RpcWire.AuthenticatedHandshake decode(ByteBuf frame) {
        return RpcWire.decodeHandshake(frame, secret);
    }

    /** Validates the proof of a received handshake: our reply's echo, or an initiator's freshness. */
    void verify(RpcConnectionContext context, RpcWire.HandshakeProof proof) {
        if (context.expectedPeer != null) {
            // Our own outbound connection: the reply must echo the nonce we sent.
            byte[] sent = context.sentNonce;
            if (sent == null || !Arrays.equals(sent, proof.peerNonce()))
                throw new CorruptedFrameException("Handshake reply does not answer this connection");
            return;
        }
        if (!Arrays.equals(ZERO_NONCE, proof.peerNonce())) throw new CorruptedFrameException("Unexpected handshake reply");
        if (secret != null) checkFresh(proof);
    }

    private byte[] newNonce() {
        if (secret == null) return ZERO_NONCE.clone();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        return nonce;
    }

    /** Initiating handshake with a secret: timestamp within the skew window and nonce never seen in it. */
    private void checkFresh(RpcWire.HandshakeProof proof) {
        long now = System.currentTimeMillis();
        if (Math.abs(now - proof.timestampMillis()) > clockSkewMillis) throw new CorruptedFrameException("Stale RPC handshake");
        long nanos = System.nanoTime();
        long expires = nanos + TimeUnit.MILLISECONDS.toNanos(2 * clockSkewMillis);
        if (seenNonces.putIfAbsent(ByteBuffer.wrap(proof.nonce()), expires) != null)
            throw new CorruptedFrameException("Replayed RPC handshake");
        // Purge at most once per skew window, so a handshake burst does not rescan the map every time.
        if (seenNonces.size() > PURGE_THRESHOLD && nanos - nextPurgeNanos >= 0) {
            nextPurgeNanos = nanos + TimeUnit.MILLISECONDS.toNanos(clockSkewMillis);
            seenNonces.values().removeIf(expiry -> expiry - nanos < 0);
        }
    }
}
