package org.adaway.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One diagnostic's observations and sockets; cancelling never touches another session. */
public final class ListenerDiagnosticSession {
    public interface Observer { void onProgress(String message); }
    private final Observer observer;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Socket> sockets = new HashSet<>();
    private java.util.function.BooleanSupplier configurationCurrent = () -> true;

    public ListenerDiagnosticSession() { this(message -> { }); }
    public ListenerDiagnosticSession(Observer observer) { this.observer = java.util.Objects.requireNonNull(observer); }
    public boolean isCancelled() { return cancelled.get(); }

    public void cancel() {
        cancelled.set(true);
        closeSockets();
    }

    /** Bind the saved target without holding the lifecycle lock during network IO. */
    void requireCurrentConfiguration(java.util.function.BooleanSupplier current) throws InterruptedIOException {
        configurationCurrent = java.util.Objects.requireNonNull(current);
        checkCancelled();
    }

    public void checkCancelled() throws InterruptedIOException {
        if (!configurationCurrent.getAsBoolean()) {
            cancel();
            throw new InterruptedIOException("监听配置已改变，请重新检测");
        }
        if (isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("检测已取消");
    }

    public void progress(String message) throws InterruptedIOException {
        checkCancelled();
        observer.onProgress(message);
        checkCancelled();
    }

    void begin() throws InterruptedIOException {
        checkCancelled();
        if (!claimed.compareAndSet(false, true)) throw new IllegalStateException("诊断会话只能执行一次");
    }

    void finish() { closeSockets(); }

    Registration register(Socket socket) throws InterruptedIOException {
        synchronized (sockets) {
            if (!isCancelled() && !Thread.currentThread().isInterrupted()) {
                sockets.add(socket);
                return new Registration(socket);
            }
        }
        close(socket);
        throw new InterruptedIOException("检测已取消");
    }

    final class Registration implements AutoCloseable {
        private final Socket socket;
        private Registration(Socket socket) { this.socket = socket; }
        @Override public void close() { synchronized (sockets) { sockets.remove(socket); } }
    }

    private void closeSockets() {
        ArrayList<Socket> active;
        synchronized (sockets) { active = new ArrayList<>(sockets); sockets.clear(); }
        for (Socket socket : active) close(socket);
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { /* Cancellation cleanup only. */ }
    }

    <T> T await(Future<T> future, long timeoutMs, String failure) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            checkCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException(failure);
            try {
                T result = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                checkCancelled();
                return result;
            } catch (TimeoutException waiting) {
                // Observe cancellation even if an OS operation ignores thread interruption.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                InterruptedIOException error = new InterruptedIOException("检测已取消");
                error.initCause(interrupted);
                throw error;
            } catch (ExecutionException error) { throw new IOException(failure, error.getCause()); }
        }
    }
}
