package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.RpcMetadata;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

class RpcMetadataValidationTest {
    @Test
    void smallHeadersAndBitmapTransitionRejectDuplicatesAtEveryPosition() {
        for (int count : new int[] {1, 4, 5, 8, 9, 64}) {
            ByteBuf input = Unpooled.buffer().writeZero(3);
            input.readerIndex(3);
            try {
                for (int i = 0; i < count; i++)
                    input.writeShort(i == 0 ? Short.MAX_VALUE : 1024 + i).writeByte(0);
                int end = input.writerIndex();
                RpcMetadata.copyOf(input, count * 3 + 3);
                for (int duplicate = 0; duplicate < count; duplicate++) {
                    input.writerIndex(end);
                    input.writeShort(duplicate == 0 ? Short.MAX_VALUE : 1024 + duplicate)
                            .writeByte(0);
                    assertThrows(
                            RpcProtocolException.class,
                            () -> RpcMetadata.copyOf(input, count * 3 + 3));
                    assertEquals(3, input.readerIndex());
                }
                input.writerIndex(end);
                RpcMetadata.copyOf(input, count * 3);
            } finally {
                input.release();
            }
        }
    }

    @Test
    void manyShortLivedVirtualThreadsValidateWithoutSharingState() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int task = 0; task < 1000; task++) {
                int count = task % 2 == 0 ? 8 : 65;
                jobs.add(
                        workers.submit(
                                () -> {
                                    assertTrue(Thread.currentThread().isVirtual());
                                    ByteBuf input = Unpooled.buffer();
                                    try {
                                        for (int key = 1; key <= count; key++)
                                            input.writeShort(key).writeByte(0);
                                        var metadata = RpcMetadata.copyOf(input, count * 3);
                                        assertEquals(count * 3, metadata.encodedLength());
                                        input.writeShort(1).writeByte(0);
                                        assertThrows(
                                                RpcProtocolException.class,
                                                () -> RpcMetadata.copyOf(input, count * 3 + 3));
                                        input.writerIndex(count * 3);
                                        assertEquals(
                                                count * 3,
                                                RpcMetadata.copyOf(input, count * 3)
                                                        .encodedLength());
                                    } finally {
                                        input.release();
                                    }
                                }));
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void acceptsEveryPositiveShortKeyWithoutQuadraticDuplicateScanning() {
        ByteBuf input = Unpooled.buffer(Short.MAX_VALUE * 3);
        try {
            for (int key = 1; key <= Short.MAX_VALUE; key++) input.writeShort(key).writeByte(0);
            var metadata = RpcMetadata.copyOf(input, input.readableBytes());
            assertEquals(Short.MAX_VALUE * 3, metadata.encodedLength());
            assertArrayEquals(new byte[0], metadata.get((short) 63));
            assertArrayEquals(new byte[0], metadata.get((short) 64));
            assertArrayEquals(new byte[0], metadata.get(Short.MAX_VALUE));
        } finally {
            input.release();
        }
    }

    @Test
    void duplicateFailureDoesNotPoisonTheNextValidation() {
        ByteBuf input = Unpooled.buffer();
        try {
            input.writeShort(Short.MAX_VALUE)
                    .writeByte(0)
                    .writeShort(64)
                    .writeByte(0)
                    .writeShort(Short.MAX_VALUE)
                    .writeByte(0);
            assertThrows(RpcProtocolException.class, () -> RpcMetadata.copyOf(input, 100));
            input.writerIndex(6);
            for (int i = 0; i < 3; i++) {
                var metadata = RpcMetadata.copyOf(input, 100);
                assertTrue(metadata.contains(Short.MAX_VALUE));
                assertTrue(metadata.contains((short) 64));
            }
        } finally {
            input.release();
        }
    }

    @Test
    void concurrentValidationDoesNotShareDuplicateState() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++)
                jobs.add(
                        workers.submit(
                                () -> {
                                    ByteBuf input = Unpooled.buffer(3000);
                                    try {
                                        start.await();
                                        for (int key = 1; key <= 1000; key++)
                                            input.writeShort(key).writeByte(0);
                                        for (int i = 0; i < 50; i++)
                                            assertEquals(
                                                    3000,
                                                    RpcMetadata.copyOf(input, 3000)
                                                            .encodedLength());
                                        return null;
                                    } finally {
                                        input.release();
                                    }
                                }));
            start.countDown();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
    }
}
