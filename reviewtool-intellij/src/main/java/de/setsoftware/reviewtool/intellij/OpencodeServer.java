package de.setsoftware.reviewtool.intellij;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.intellij.OpencodeClient.OpencodeException;

/**
 * Starts a local opencode server ({@code opencode serve}) for the project when the AI support is
 * used for the first time and stops it when the project is closed. The server only listens on
 * 127.0.0.1 and is protected with a random password, so that other local processes cannot use it.
 * It runs in the project directory, so the model can read the project's files (and opencode uses the
 * project's opencode configuration, if there is one).
 */
@Service(Service.Level.PROJECT)
public final class OpencodeServer implements Disposable {

    private static final int STARTUP_TIMEOUT_MS = 60_000;
    private static final int OUTPUT_LINES_KEPT = 20;

    private final Project project;
    private Process process;
    private OpencodeClient client;
    private String startedWith;
    /** Stops opencode if the IDE is terminated without closing the project (e.g. killed with SIGTERM). */
    private Thread shutdownHook;
    private final Deque<String> output = new ArrayDeque<>();

    public OpencodeServer(Project project) {
        this.project = project;
    }

    public static OpencodeServer getInstance(Project project) {
        return project.getService(OpencodeServer.class);
    }

    /**
     * Returns a client for the running server, starting it if necessary. Must not be called on the EDT
     * (starting opencode takes a few seconds).
     *
     * @param canceled Is polled while waiting for the server.
     */
    synchronized OpencodeClient getClient(BooleanSupplier canceled) {
        final String executable = this.executable();
        if (this.process != null && (!this.process.isAlive() || !executable.equals(this.startedWith))) {
            this.stop();
        }
        if (this.process == null) {
            final Started started = this.startProcess(executable, canceled);
            this.process = started.process;
            this.client = started.client;
            this.startedWith = executable;
            final Process toStop = started.process;
            this.shutdownHook = new Thread(() -> destroy(toStop), "CoRT opencode shutdown");
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        }
        return this.client;
    }

    /**
     * Starts opencode with the given executable (e.g. from the not yet saved settings), checks that
     * it works and stops it again. Returns a report for the user.
     */
    String check(String executable, BooleanSupplier canceled) {
        final Started started = this.startProcess(executable, canceled);
        try {
            final String version = started.client.health();
            final String model = started.client.configuredModel();
            return "opencode " + version + " works.\n\nModel (from the opencode configuration): "
                    + (model == null ? "opencode's default" : model);
        } finally {
            destroy(started.process);
        }
    }

    /**
     * A started opencode server.
     */
    private static final class Started {
        private final Process process;
        private final OpencodeClient client;

        Started(Process process, OpencodeClient client) {
            this.process = process;
            this.client = client;
        }
    }

    /**
     * Resolves a command name without a path and without an extension on Windows with the PATH and the
     * extensions of PATHEXT, e.g. "opencode" to "C:\Users\...\npm\opencode.cmd" (starting a
     * process only finds ".exe" files by itself). Everything else is returned unchanged.
     */
    static String resolveExecutable(String executable) {
        if (!SystemInfo.isWindows || executable.contains("/") || executable.contains("\\")
                || executable.contains(".")) {
            return executable;
        }
        return PathEnvironmentVariableUtil.findExecutableInWindowsPath(executable, executable);
    }

    private String executable() {
        final String configured = ReviewToolSettings.getInstance(this.project).getState().opencodePath;
        return configured == null || configured.trim().isEmpty() ? "opencode" : configured.trim();
    }

    private Started startProcess(String executable, BooleanSupplier canceled) {
        final int port = freePort();
        final String password = randomPassword();
        final GeneralCommandLine commandLine = new GeneralCommandLine(resolveExecutable(executable))
                .withParameters("serve", "--hostname", "127.0.0.1", "--port", Integer.toString(port))
                .withRedirectErrorStream(true)
                .withEnvironment("OPENCODE_SERVER_PASSWORD", password);
        final String basePath = this.project.getBasePath();
        if (basePath != null && new File(basePath).isDirectory()) {
            commandLine.setWorkDirectory(basePath);
        }
        synchronized (this.output) {
            this.output.clear();
        }
        final Process started;
        try {
            // GeneralCommandLine starts batch files (like the "opencode.cmd" npm installs on Windows) via
            // cmd.exe with the right quoting
            started = commandLine.createProcess();
        } catch (final ExecutionException e) {
            throw new OpencodeException("opencode could not be started (\"" + executable + "\" was not found or"
                    + " cannot be executed). Install opencode or set the path to it in the settings.", true, e);
        }
        this.collectOutput(started);
        final OpencodeClient newClient = new OpencodeClient(URI.create("http://127.0.0.1:" + port + "/"), password);
        final long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        while (true) {
            if (canceled.getAsBoolean()) {
                destroy(started);
                throw new CancellationException();
            }
            if (!started.isAlive()) {
                throw new OpencodeException("opencode stopped right after the start (exit code "
                        + started.exitValue() + "). Its output:\n" + this.lastOutput(), true, null);
            }
            try {
                final String version = newClient.health(Duration.ofSeconds(3));
                Logger.info("started opencode " + version + " for CoRT on port " + port);
                break;
            } catch (final OpencodeException e) {
                if (System.currentTimeMillis() > deadline) {
                    destroy(started);
                    throw new OpencodeException("opencode did not start within " + STARTUP_TIMEOUT_MS / 1000
                            + " seconds. Its output:\n" + this.lastOutput(), false, e);
                }
            }
            try {
                Thread.sleep(250);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                destroy(started);
                throw new CancellationException();
            }
        }
        return new Started(started, newClient);
    }

    private void collectOutput(Process started) {
        final Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (this.output) {
                        this.output.addLast(line);
                        while (this.output.size() > OUTPUT_LINES_KEPT) {
                            this.output.removeFirst();
                        }
                    }
                }
            } catch (final IOException e) {
                // the process has ended
            }
        }, "CoRT opencode output");
        reader.setDaemon(true);
        reader.start();
    }

    private String lastOutput() {
        synchronized (this.output) {
            return this.output.isEmpty() ? "(no output)" : String.join("\n", this.output);
        }
    }

    /**
     * Stops the server (it is started again when it is needed the next time, e.g. with other settings).
     */
    synchronized void stop() {
        if (this.process != null) {
            destroy(this.process);
        }
        if (this.shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
            } catch (final IllegalStateException e) {
                // the JVM is shutting down already
            }
            this.shutdownHook = null;
        }
        this.process = null;
        this.client = null;
        this.startedWith = null;
    }

    private static void destroy(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    @Override
    public void dispose() {
        this.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new OpencodeException("No free port for opencode: " + e, false, e);
        }
    }

    private static String randomPassword() {
        final byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
