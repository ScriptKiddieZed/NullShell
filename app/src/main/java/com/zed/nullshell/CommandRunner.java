package com.zed.nullshell;

import java.util.List;

/**
 * Types rendered snippet text into a session on a background thread, honouring &lt;sleep&gt;
 * tokens and pacing lines so the shell is never flooded. Can be cancelled.
 */
public final class CommandRunner {
    public interface Done { void onDone(boolean cancelled); }

    private volatile boolean cancelled;
    private Thread thread;

    public static boolean hasPauses(List<KeyTokens.Action> actions) {
        for (KeyTokens.Action a : actions) if (a.data == null) return true;
        return false;
    }

    public void start(KaliSession target, List<KeyTokens.Action> actions, Done done) {
        thread = new Thread(() -> {
            try {
                for (KeyTokens.Action a : actions) {
                    if (cancelled || !target.isAlive()) break;
                    if (a.data == null) { Thread.sleep(a.sleepMs); continue; }
                    target.write(a.data);
                    // after Enter give the shell a moment before the next line arrives
                    if (a.data.length == 1 && a.data[0] == '\r') Thread.sleep(40);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (done != null) done.onDone(cancelled);
        }, "snippet-runner");
        thread.setDaemon(true);
        thread.start();
    }

    public void cancel() {
        cancelled = true;
        if (thread != null) thread.interrupt();
    }
}
