package org.adaway.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds the entire subprocess exchange, including blocking pipe reads/writes. */
final class BoundedProcessResponse {
    private BoundedProcessResponse() {}

    static List<String> ncCommand(String executable, String applet, String host, int port) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (applet != null) command.add(applet);
        command.addAll(Arrays.asList("-w", "3", host, Integer.toString(port)));
        return command;
    }

    static String exchange(Process process, String request, long timeoutMs, int limit)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Thread errors = new Thread(() -> discard(process.getErrorStream()), "adaway-nc-stderr");
        FutureTask<String> result = new FutureTask<>(() -> {
            try (InputStream output = process.getInputStream()) {
                try (OutputStream input = process.getOutputStream()) {
                    input.write(request.getBytes(StandardCharsets.UTF_8));
                }
                return read(output, limit);
            }
        });
        Thread reader = new Thread(result, "adaway-nc-response");
        errors.setDaemon(true);
        reader.setDaemon(true);
        errors.start();
        reader.start();
        try {
            String response = result.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new IOException("Local response process did not finish");
            }
            if (process.exitValue() != 0) throw new IOException("Local response process failed");
            return response;
        } catch (TimeoutException error) {
            throw new IOException("Local response deadline exceeded", error);
        } catch (ExecutionException error) {
            throw new IOException("Local response read failed", error.getCause());
        } finally {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            close(process.getOutputStream());
            close(process.getInputStream());
            close(process.getErrorStream());
            result.cancel(true);
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long time = deadline - System.nanoTime();
        if (time <= 0) throw new TimeoutException("Local response deadline exceeded");
        return time;
    }

    static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Local response exceeds limit");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void discard(InputStream input) {
        try (InputStream stream = input) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) { /* Drain without storing stderr. */ }
        } catch (IOException ignored) { /* Process termination also closes the pipe. */ }
    }

    private static void close(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* Best-effort termination cleanup. */ }
    }
}
