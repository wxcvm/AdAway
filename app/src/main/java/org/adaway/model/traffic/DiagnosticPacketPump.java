package org.adaway.model.traffic;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded slow transport workers and a single response writer; never waits in submit. */
public final class DiagnosticPacketPump implements AutoCloseable {
    public static final int CAPACITY = 8;
    @FunctionalInterface public interface Processor { byte[] process(byte[] packet); }
    @FunctionalInterface public interface Writer { void write(byte[] packet) throws IOException; }
    private final ThreadPoolExecutor workers;
    private final ArrayBlockingQueue<byte[]> replies = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private final Runnable cancelTransport;

    public DiagnosticPacketPump(Processor processor, Writer output, Runnable cancelTransport,
                                Runnable writeFailure) {
        this.cancelTransport = cancelTransport;
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACITY), task -> {
                    Thread thread = new Thread(task, "diagnostic-transport");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        writer = new Thread(() -> {
            try {
                while (!closed.get()) {
                    byte[] reply = replies.take();
                    if (!closed.get()) output.write(reply);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                if (!closed.get()) writeFailure.run();
            } finally {
                close();
            }
        }, "diagnostic-tun-writer");
        writer.setDaemon(true);
        writer.start();
        this.processor = processor;
    }
    private final Processor processor;

    public boolean submit(byte[] packet, int length) {
        if (closed.get() || packet == null || length < 0 || length > packet.length
                || length > DiagnosticTunPlan.MTU) return false;
        byte[] owned = Arrays.copyOf(packet, length);
        try {
            workers.execute(() -> {
                if (closed.get()) return;
                try {
                    byte[] reply = processor.process(owned);
                    if (reply != null) respond(reply);
                } catch (RuntimeException failure) {
                    dropped.incrementAndGet();
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            dropped.incrementAndGet();
            return false;
        }
    }

    /** The reader can enqueue a direct Echo without waiting behind network timeouts. */
    public boolean respond(byte[] reply) {
        if (closed.get() || reply == null || reply.length > DiagnosticTunPlan.MTU) return false;
        if (replies.offer(reply.clone())) return true;
        dropped.incrementAndGet();
        return false;
    }

    public long dropped() { return dropped.get(); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelTransport.run();
        workers.shutdownNow();
        replies.clear();
        writer.interrupt();
    }

    public boolean awaitStopped(long milliseconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        boolean done = workers.awaitTermination(milliseconds, TimeUnit.MILLISECONDS);
        long left = deadline - System.nanoTime();
        if (left > 0) writer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
        return done && !writer.isAlive();
    }
}
