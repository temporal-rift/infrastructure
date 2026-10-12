package io.github.temporalrift.systemtest.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs container-runtime commands against the deployment's own projects. */
final class Docker {

    private Docker() {}

    /** Runs {@code docker <args>}, failing with its output on a non-zero exit, and returns its output. */
    static String run(String... args) {
        var command = new ArrayList<String>();
        command.add("docker");
        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "Command failed (" + process.exitValue() + "): " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot run " + String.join(" ", command), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Stops one service of a Compose project. */
    static void stopService(String project, String service) {
        run("compose", "-p", project, "stop", service);
    }

    /** Kills one service of a Compose project without a graceful shutdown. */
    static void killService(String project, String service) {
        run("compose", "-p", project, "kill", service);
    }

    /** Starts one service of a Compose project that was stopped or killed. */
    static void startService(String project, String service) {
        run("compose", "-p", project, "start", service);
    }

    /** Runs a SQL statement against the workbench database and returns the command output. */
    static String workbenchSql(String stackProject, String sql) {
        return run(
                "compose",
                "-p",
                stackProject,
                "exec",
                "-T",
                "workbench-postgres",
                "psql",
                "-U",
                "workbench",
                "-d",
                "workbench",
                "-At",
                "-c",
                sql);
    }

    /** Whether any container carries the label {@code name=value}. */
    static boolean containerExists(String label) {
        return !run("ps", "-a", "-q", "--filter", "label=" + label).isBlank();
    }
}
