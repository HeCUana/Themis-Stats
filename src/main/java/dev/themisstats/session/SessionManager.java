package dev.themisstats.session;

import dev.themisstats.ThemisStats;
import dev.themisstats.identity.Identity;
import dev.themisstats.identity.IdentityResolver;
import dev.themisstats.metric.MetricPipeline;
import dev.themisstats.storage.AccountRepository;
import dev.themisstats.storage.SessionUpdate;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the live sessions of the server.
 *
 * <p>A session is created for every actually-online player object, and it is settled when that
 * object goes away. A session therefore never spans two objects of the same account: a human that
 * is replaced by a Shadow, or a player that respawns, always gets a fresh session, while the
 * accumulated totals stay continuous.</p>
 */
public final class SessionManager {

    /** A live session together with the markers that describe how it will end. */
    public static final class LiveSession {
        private final Session session;
        private volatile SessionEndReason pendingEndReason;
        private volatile boolean died;

        LiveSession(Session session) {
            this.session = session;
        }

        public Session session() {
            return session;
        }

        /** @return the session of the given player, or {@code null} if the player has no session */
        public Session get() {
            return session;
        }
    }

    private final Map<ServerPlayer, LiveSession> live = new ConcurrentHashMap<>();
    private final MetricPipeline pipeline;
    private final AccountRepository accounts;
    private final String bootId;
    private final long bootWallMillis;

    public SessionManager(MetricPipeline pipeline, AccountRepository accounts, String bootId) {
        this.pipeline = pipeline;
        this.accounts = accounts;
        this.bootId = bootId;
        this.bootWallMillis = System.currentTimeMillis();
    }

    public String bootId() {
        return bootId;
    }

    public long bootWallMillis() {
        return bootWallMillis;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Creates the session of a player that just came online.
     */
    public LiveSession onPlayerLogin(ServerPlayer player) {
        Identity identity = IdentityResolver.resolve(player);
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        String dimension = player.level().dimension().identifier().toString();
        Session session = new Session(UUID.randomUUID().toString(), uuid, identity, bootId, now, nanos,
                dimension, player.getX(), player.getY(), player.getZ(), true);
        LiveSession liveSession = new LiveSession(session);
        live.put(player, liveSession);
        accounts.touchAccount(uuid, name, now);
        ThemisStats.LOGGER.info("[ThemisStats] 会话开始：{}（{}）身份={} 会话={}",
                name, uuid, identity.displayName(), session.id());
        return liveSession;
    }

    /**
     * Settles the session of a player that went offline.
     */
    public void onPlayerLogout(ServerPlayer player) {
        LiveSession liveSession = live.remove(player);
        if (liveSession == null) {
            return;
        }
        SessionEndReason reason = liveSession.pendingEndReason;
        if (reason == null) {
            if (liveSession.died) {
                reason = SessionEndReason.DEATH;
            } else if (identityOf(player).botLike()) {
                // A fake went offline without an observable cause. The specification requires
                // "offline, reason unknown" instead of guessing an administrator action.
                reason = SessionEndReason.UNKNOWN;
            } else {
                reason = SessionEndReason.NORMAL_DISCONNECT;
            }
        }
        settle(liveSession.session, reason, player);
    }

    /**
     * Handles a respawn: the old player object is replaced by a new one, so the old session is
     * settled and a new one begins. The totals stay continuous.
     */
    public void onPlayerRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        LiveSession old = live.remove(oldPlayer);
        if (old != null) {
            old.pendingEndReason = SessionEndReason.DEATH;
            settle(old.session, SessionEndReason.DEATH, oldPlayer);
        }
        onPlayerLogin(newPlayer);
    }

    /**
     * Settles every live session, e.g. when the server stops normally.
     */
    public void settleAll(SessionEndReason reason) {
        for (ServerPlayer player : new ArrayList<>(live.keySet())) {
            LiveSession liveSession = live.remove(player);
            if (liveSession != null) {
                settle(liveSession.session, reason, player);
            }
        }
    }

    private void settle(Session session, SessionEndReason reason, ServerPlayer player) {
        if (session.isSettled()) {
            return;
        }
        long nanos = System.nanoTime();
        session.closeOpenPause(nanos);
        long wall = System.currentTimeMillis();
        if (player != null) {
            session.updateLastKnown(player.level().dimension().identifier().toString(),
                    player.getX(), player.getY(), player.getZ());
        }
        SessionUpdate update = new SessionUpdate(session.id(), session.bootId(), session.accountUuid(),
                session.identity().id(), session.startWallMillis(), wall, reason.id(), wall, nanos,
                session.onlineMillis(), session.collectedMillis(),
                session.initialDimension(), session.initialX(), session.initialY(), session.initialZ(),
                session.lastDimension(), session.lastX(), session.lastY(), session.lastZ(),
                null, null, null, null, true, null);
        pipeline.submitSession(update);
        ThemisStats.LOGGER.info("[ThemisStats] 会话结束：{} 身份={} 结束情况={} 在线={}ms 有效采集={}ms",
                session.accountUuid(), session.identity().displayName(), reason.displayName(),
                session.onlineMillis(), session.collectedMillis());
    }

    // ------------------------------------------------------------------ access

    /** @return the live session of the given player, or {@code null} */
    public LiveSession liveSession(ServerPlayer player) {
        return player == null ? null : live.get(player);
    }

    /** @return the session of the given player, or {@code null} */
    public Session sessionOf(ServerPlayer player) {
        LiveSession liveSession = liveSession(player);
        return liveSession == null ? null : liveSession.session();
    }

    /** @return all live sessions */
    public List<LiveSession> liveSessions() {
        return new ArrayList<>(live.values());
    }

    /** @return the number of live sessions per identity */
    public Map<Identity, Integer> onlineCounts() {
        Map<Identity, Integer> counts = new java.util.EnumMap<>(Identity.class);
        for (LiveSession liveSession : live.values()) {
            counts.merge(liveSession.session().identity(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------ markers

    /** Marks that the object is about to be disconnected for the given reason. */
    public void markEndReason(ServerPlayer player, SessionEndReason reason) {
        LiveSession liveSession = liveSession(player);
        if (liveSession != null) {
            liveSession.pendingEndReason = reason;
        }
    }

    /** Marks that the object died. */
    public void markDeath(ServerPlayer player) {
        LiveSession liveSession = liveSession(player);
        if (liveSession != null) {
            liveSession.died = true;
        }
    }

    /**
     * Applies a change of the recording switch to all live sessions.
     *
     * @param enabled whether metrics are collected now
     */
    public void setCollecting(boolean enabled) {
        long nanos = System.nanoTime();
        for (LiveSession liveSession : live.values()) {
            liveSession.session().setCollecting(nanos, enabled);
        }
    }

    /**
     * @return whether the given player's session is currently collecting metrics
     */
    public boolean isCollecting(ServerPlayer player) {
        Session session = sessionOf(player);
        return session != null && session.isCollecting();
    }

    private Identity identityOf(ServerPlayer player) {
        return IdentityResolver.resolve(player);
    }
}
