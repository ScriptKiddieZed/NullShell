package com.zed.nullshell;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds all terminal tabs. Index 0 is the always-present "Console" tab (install/import logs,
 * never backed by a process). All methods must be called on the main thread.
 */
public final class SessionManager {
    public interface Callback {
        void onTabsChanged();
        void onScreen(KaliSession s);
        void onExit(KaliSession s, int code);
    }

    private static final SessionManager INSTANCE = new SessionManager();
    public static SessionManager get() { return INSTANCE; }

    private final List<KaliSession> sessions = new ArrayList<>();
    private final KaliSession console = new KaliSession(0);
    private int active;
    private int counter;
    private Callback callback;
    private Runnable serviceObserver;

    private SessionManager() {
        sessions.add(console);
        console.setListener(relay);
    }

    private final KaliSession.Listener relay = new KaliSession.Listener() {
        @Override public void onScreenChanged(KaliSession s) { if (callback != null) callback.onScreen(s); }
        @Override public void onExit(KaliSession s, int code) {
            if (callback != null) { callback.onExit(s, code); callback.onTabsChanged(); }
            notifyService();
        }
    };

    public void setCallback(Callback c) { callback = c; }
    /** Used by KaliService to refresh/dismiss its notification as sessions come and go. */
    public void setServiceObserver(Runnable r) { serviceObserver = r; }
    public void notifyService() { Runnable r = serviceObserver; if (r != null) r.run(); }
    public KaliSession console() { return console; }
    /** Keeps only the newest {@code keep} history lines of every tab. */
    public void trimScrollback(int keep) { for (KaliSession s : sessions) s.trimScrollback(keep); }
    public List<KaliSession> all() { return sessions; }
    public KaliSession active() { return sessions.get(Math.min(active, sessions.size() - 1)); }

    public KaliSession create() {
        KaliSession s = new KaliSession(++counter);
        s.setListener(relay);
        sessions.add(s);
        active = sessions.size() - 1;
        if (callback != null) callback.onTabsChanged();
        return s;
    }

    public void select(KaliSession s) {
        int i = sessions.indexOf(s);
        if (i < 0) return;
        active = i;
        if (callback != null) callback.onTabsChanged();
    }

    public void close(KaliSession s) {
        if (s == console) return;
        int i = sessions.indexOf(s);
        if (i < 0) return;
        s.stop();
        s.setListener(null);
        sessions.remove(i);
        if (active >= i) active = Math.max(0, active - 1);
        if (callback != null) callback.onTabsChanged();
        notifyService();
    }

    public int aliveCount() {
        int n = 0;
        for (KaliSession s : sessions) if (s != console && s.isAlive()) n++;
        return n;
    }

    public void stopAll() {
        for (KaliSession s : sessions) if (s != console) s.stop();
        if (callback != null) callback.onTabsChanged();
        notifyService();
    }
}
