package cn.managame.core.write;

/**
 * Two reusable buffers for one physical table. The table monitor protects add and swap together;
 * volatile publication alone cannot stop a producer from writing a previously captured buffer.
 * The assigned save worker exclusively owns the detached buffer until it clears it.
 */
final class DoubleWriteBuffer {
    private volatile WriteBuffer active = new WriteBuffer();
    private WriteBuffer spare = new WriteBuffer();

    WriteBuffer active() { return active; }

    WriteBuffer swap() {
        if (spare.submissions() != 0) throw new IllegalStateException("Save buffer is still in use");
        WriteBuffer detached = active;
        active = spare;
        spare = detached;
        return detached;
    }
}