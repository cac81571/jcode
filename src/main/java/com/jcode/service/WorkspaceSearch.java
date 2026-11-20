package com.jcode.service;

import com.jcode.model.SearchMatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Workspace search used by the sidebar. Prefers ripgrep and falls back to
 * {@link SearchService} when the binary cannot be found or started.
 * Callbacks run on the caller thread (a background worker, never the EDT).
 */
public final class WorkspaceSearch {

    public enum Engine {
        RIPGREP,
        JAVA
    }

    public record Request(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) {
    }

    public record Outcome(Engine engine, boolean cancelled, String summarySuffix, String error) {

        public static Outcome ripgrep(String suffix) {
            return new Outcome(Engine.RIPGREP, false, suffix, null);
        }

        public static Outcome cancelled(Engine engine) {
            return new Outcome(engine, true, null, null);
        }

        public static Outcome failed(String error) {
            return new Outcome(Engine.RIPGREP, false, null, error);
        }

        public static Outcome javaFallback(String suffix) {
            return new Outcome(Engine.JAVA, false, suffix, null);
        }
    }

    /**
     * Cancellation token. {@link #cancel()} is safe to call from the EDT and
     * kills the ripgrep process if one is running.
     */
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Process> active = new AtomicReference<>();

        public void cancel() {
            cancelled.set(true);
            Process process = active.get();
            if (process != null) {
                Processes.kill(process);
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void attach(Process process) {
            active.set(process);
            if (cancelled.get()) {
                Processes.kill(process);
            }
        }

        void detach(Process process) {
            active.compareAndSet(process, null);
        }
    }

    private WorkspaceSearch() {
    }

    public static Outcome run(
            Path ripgrep,
            Request request,
            Handle handle,
            Consumer<List<SearchMatch>> onBatch
    ) {
        Handle token = handle == null ? new Handle() : handle;
        Consumer<List<SearchMatch>> batch = onBatch == null ? ignored -> {
        } : onBatch;
        if (token.isCancelled()) {
            return Outcome.cancelled(ripgrep == null ? Engine.JAVA : Engine.RIPGREP);
        }
        if (ripgrep != null) {
            try {
                return RipgrepSearch.execute(ripgrep, request, token, batch);
            } catch (IOException ex) {
                if (token.isCancelled()) {
                    return Outcome.cancelled(Engine.RIPGREP);
                }
                Outcome fallback = runJava(request, token, batch, "Java・ripgrep 起動失敗");
                return fallback;
            }
        }
        return runJava(request, token, batch, "Java・ripgrep なし");
    }

    private static Outcome runJava(
            Request request,
            Handle handle,
            Consumer<List<SearchMatch>> onBatch,
            String suffix
    ) {
        try {
            List<SearchMatch> matches = SearchService.findInWorkspace(
                    request.root(),
                    request.query(),
                    request.matchCase(),
                    request.wholeWord(),
                    request.regex(),
                    request.includeFiles(),
                    request.includeDirs(),
                    request.excludeFiles(),
                    request.excludeDirs(),
                    handle::isCancelled
            );
            if (handle.isCancelled()) {
                return Outcome.cancelled(Engine.JAVA);
            }
            if (!matches.isEmpty()) {
                onBatch.accept(matches);
            }
            return Outcome.javaFallback(suffix);
        } catch (Exception ex) {
            if (handle.isCancelled()) {
                return Outcome.cancelled(Engine.JAVA);
            }
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Outcome(Engine.JAVA, false, null, message);
        }
    }
}
