package com.jcode.service;

/**
 * Stops an external search process and any children it spawned.
 */
final class Processes {

    private Processes() {
    }

    static void kill(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            process.descendants().forEach(child -> {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            });
        } catch (Exception ignored) {
            // best-effort; the process itself is still destroyed below
        }
        process.destroyForcibly();
    }
}
