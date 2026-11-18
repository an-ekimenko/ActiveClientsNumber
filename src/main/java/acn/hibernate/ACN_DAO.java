package acn.hibernate;

import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.QueryTimeoutException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.LockAcquisitionException;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import acn.config.ACNApiConfig;
import acn.model.ACN_Entity;

/**
 * Data Access Object (DAO) utility for handling database operations via Hibernate.
 * Implements resilient batch persistence with exponential backoff, jitter, and time-budget limits
 * to survive transient MS SQL Server outages or deadlock events.
 */
@Singleton
public class ACN_DAO {

    private static final Logger logger = LogManager.getLogger(ACN_DAO.class);

    private static final long DEFAULT_MAX_TIMEOUT_MS = 300_000L;
    private static final long DEFAULT_INITIAL_TIMEOUT_MS = 10_000L;
    private static final double MULTIPLIER = 2.0;
    private static final double RANDOMIZATION_FACTOR = 0.5;
    private static final int BATCH_SIZE = 50;

    private final SessionFactory sessionFactory;
    private final long initialIntervalMs;
    private final long maxTimeoutMs;
    private final LongConsumer sleeper;

    @Inject
    public ACN_DAO(SessionFactory sessionFactory, ACNApiConfig config) {
        this(sessionFactory, config, value -> {
			try {
				Thread.sleep(value);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		});
    }

    // Package-private constructor allowing deterministic sleep interception in unit tests
    ACN_DAO(SessionFactory sessionFactory, ACNApiConfig config, LongConsumer sleeper) {
        this.sessionFactory = sessionFactory;
        this.initialIntervalMs = (config != null && config.getInitialTimeoutMs() > 0)
                ? config.getInitialTimeoutMs()
                : DEFAULT_INITIAL_TIMEOUT_MS;
        this.maxTimeoutMs = (config != null && config.getMaxTimeoutMs() > 0)
                ? config.getMaxTimeoutMs()
                : DEFAULT_MAX_TIMEOUT_MS;
        this.sleeper = sleeper;
    }

    /**
     * Saves or updates ACN_Entity entities in MS SQL using batch processing with jittered exponential backoff.
     *
     * @param entities the list of entities to be synchronized with the database
     */
    public void saveOrUpdateBatch(List<ACN_Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        long startTimeMs = System.currentTimeMillis();
        int attempt = 0;

        while (true) {
            attempt++;
            try {
                executeBatchTransaction(entities);
                if (attempt > 1) {
                    logger.info("Database batch persistence recovered and succeeded on attempt #{}.", attempt);
                }
                return;
            } catch (Exception ex) {
                if (!isTransientDatabaseException(ex)) {
                    logger.error("Non-retryable database error encountered on attempt #{}. Aborting immediately.", attempt, ex);
                    throw ex;
                }

                long elapsedMs = System.currentTimeMillis() - startTimeMs;
                long remainingBudgetMs = maxTimeoutMs - elapsedMs;

                if (remainingBudgetMs <= 0) {
                    logger.error("Database retry time budget ({} ms) exhausted after {} attempt(s) (elapsed: {} ms). Aborting batch.",
                            maxTimeoutMs, attempt, elapsedMs, ex);
                    throw ex;
                }

                long rawDelayMs = calculateBackoffWithJitter(initialIntervalMs, attempt, maxTimeoutMs);
                long actualDelayMs = Math.min(rawDelayMs, remainingBudgetMs);

                logger.warn("Transient database failure on attempt #{} ({}). Retrying in {} ms (remaining budget: {} ms)...",
                        attempt, ex.getMessage(), actualDelayMs, remainingBudgetMs);

                sleeper.accept(actualDelayMs);
                if (Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException("Database retry backoff sleep was interrupted", ex);
                }
            }
        }
    }

    private void executeBatchTransaction(List<ACN_Entity> entities) {
        Transaction transaction = null;

        try (Session session = sessionFactory.openSession()) {
            transaction = session.beginTransaction();

            int counter = 0;
            for (ACN_Entity entity : entities) {
                session.merge(entity);

                if (++counter % BATCH_SIZE == 0) {
                    session.flush();
                    session.clear();
                }
            }

            session.flush();
            session.clear();
            transaction.commit();

            logger.info("The batch of {} entities has been successfully saved/updated in MS SQL.", entities.size());
        } catch (Exception ex) {
            if (transaction != null && transaction.isActive()) {
                try {
                    transaction.rollback();
                } catch (Exception rollbackEx) {
                    logger.warn("Failed to rollback transaction after database error: {}", rollbackEx.getMessage());
                }
            }
            throw ex;
        }
    }

    /**
     * Evaluates the exception cause chain to distinguish transient connectivity/deadlock failures
     * from permanent schema or constraint violations.
     */
    boolean isTransientDatabaseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof JDBCConnectionException
                    || current instanceof LockAcquisitionException
                    || current instanceof QueryTimeoutException
                    || current instanceof SQLTransientException
                    || current instanceof SQLTimeoutException
                    || current instanceof SocketException) {
                return true;
            }

            if (current instanceof SQLException sqlEx) {
                int errorCode = sqlEx.getErrorCode();
                String sqlState = sqlEx.getSQLState();
                // MS SQL Server transient codes: 1205 (deadlock), -2 (client timeout), 40xxx (transaction rollback/serialization)
                if (errorCode == 1205 || errorCode == -2 || (sqlState != null && sqlState.startsWith("08"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Safe calculation of exponential backoff with jitter without long overflow risks.
     */
    long calculateBackoffWithJitter(long initialIntervalMs, int attempt, long maxCapMs) {
        double rawInterval = initialIntervalMs * Math.pow(MULTIPLIER, Math.min(attempt - 1, 30));
        long cappedInterval = (long) Math.min(rawInterval, maxCapMs);

        long delta = (long) (cappedInterval * RANDOMIZATION_FACTOR);
        long minDelay = Math.max(0L, cappedInterval - delta);
        long maxDelay = Math.max(minDelay, cappedInterval + delta);

        return ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1);
    }
}
