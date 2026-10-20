package dev.themisstats.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps the database connection so that only one thread uses it at a time.
 *
 * <p><b>Why this is needed.</b> The statistics database is opened once per world, and the game
 * thread (the commands and the sidebar) as well as the background thread (the writer and the run
 * alerts) both use it. A SQLite connection is <em>not</em> thread safe: two threads that use the
 * same connection at the same time can end up in the same transaction, so a rollback would throw
 * away the other thread's data, and a read can see rows that were never committed.</p>
 *
 * <p>The wrapper therefore puts a re-entrant lock in front of every call. The lock is held for the
 * <em>whole</em> transaction: as soon as the auto-commit is switched off, the lock stays held until
 * the transaction is committed or rolled back. Everything that happens in between is therefore
 * visible to other threads only after the commit.</p>
 */
final class SynchronizedConnection implements InvocationHandler {

    private final Connection delegate;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean transactionOpen;

    private SynchronizedConnection(Connection delegate) {
        this.delegate = delegate;
    }

    /**
     * @param delegate the real connection
     * @return a connection that may be used by several threads at the same time
     */
    static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                SynchronizedConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new SynchronizedConnection(delegate));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("setAutoCommit".equals(name) && args != null && args.length == 1
                && Boolean.FALSE.equals(args[0])) {
            // The transaction starts: the lock is held until it is finished.
            lock.lock();
            try {
                transactionOpen = true;
                return call(method, args);
            } catch (Throwable t) {
                transactionOpen = false;
                lock.unlock();
                throw t;
            }
        }
        if (("commit".equals(name) || "rollback".equals(name)) && transactionOpen) {
            try {
                return call(method, args);
            } finally {
                transactionOpen = false;
                lock.unlock();
            }
        }
        lock.lock();
        try {
            return call(method, args);
        } finally {
            lock.unlock();
        }
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
