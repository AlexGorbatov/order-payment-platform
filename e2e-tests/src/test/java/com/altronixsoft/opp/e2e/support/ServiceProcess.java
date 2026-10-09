package com.altronixsoft.opp.e2e.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One of the services as a real operating-system process: {@code java -jar <the executable jar the build produced>}.
 * It can be stopped gracefully or killed ({@code kill -9}, a crash) and started again on the same port, which is how the
 * chaos scenarios restart a service in the middle of its work.
 *
 * <p>Output goes to {@code <logs>/<name>.log} (appended across restarts); the tail of it is part of every start-up
 * failure message.
 */
public final class ServiceProcess {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final String name;
    private final Path jar;
    private final Path logFile;
    private final Map<String, String> properties;
    private final int port;
    private Process process;

    ServiceProcess(String name, Path jar, Path logDirectory, Map<String, String> properties) {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "No executable jar for " + name + " at " + jar + ". Build it first: ./mvnw -DskipTests package");
        }
        this.name = name;
        this.jar = jar;
        this.logFile = logDirectory.resolve(name + ".log");
        this.properties = new LinkedHashMap<>(properties);
        this.port = freePort();
        try {
            Files.createDirectories(logDirectory);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String name() {
        return name;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public Path logFile() {
        return logFile;
    }

    public synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Starts the process and returns when {@code /actuator/health} says UP. */
    public synchronized void start(Duration timeout) {
        if (isAlive()) {
            throw new IllegalStateException(name + " is already running");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx512m");
        command.add("-XX:+UseSerialGC");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-Duser.timezone=UTC");
        command.add("-jar");
        command.add(jar.toString());
        command.add("--server.port=" + port);
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start " + name, e);
        }
        awaitHealthy(timeout);
    }

    /** SIGTERM: the service shuts down the way it does when an operator stops it. */
    public synchronized void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** SIGKILL: no shutdown hooks, no graceful anything; whatever was in flight is simply gone. */
    public synchronized void kill() {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The last {@code lines} lines of the service's log, for failure messages. */
    public String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
    }

    private void awaitHealthy(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
                .timeout(Duration.ofSeconds(2))
                .build();
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        name + " exited with " + process.exitValue() + " while starting:\n" + logTail(60));
            }
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            sleep(250);
        }
        kill();
        throw new IllegalStateException(name + " was not healthy within " + timeout + ":\n" + logTail(60));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port", e);
        }
    }
}
