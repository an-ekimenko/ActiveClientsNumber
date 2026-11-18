package acn.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import acn.config.ACNApiConfig;
import acn.model.ACN_Entity;
import acn.model.ACN_PK;

@ExtendWith(MockitoExtension.class)
class ACN_DAOTest {

    @Mock
    private SessionFactory sessionFactory;

    @Mock
    private Session session;

    @Mock
    private Transaction transaction;

    private ACNApiConfig config;
    private List<Long> recordedDelays;
    private ACN_DAO acnDao;

    @BeforeEach
    void setUp() {
        config = new ACNApiConfig();
        config.setInitialTimeoutMs(1000L);
        config.setMaxTimeoutMs(10_000L);

        recordedDelays = new ArrayList<>();
        acnDao = new ACN_DAO(sessionFactory, config, recordedDelays::add);

        lenient().when(sessionFactory.openSession()).thenReturn(session);
        lenient().when(session.beginTransaction()).thenReturn(transaction);
        lenient().when(transaction.isActive()).thenReturn(true);
    }

    @Test
    @DisplayName("Should ignore null or empty entity lists without opening a Hibernate session")
    void saveOrUpdateBatch_EmptyOrNullList_SkipsSession() {
        acnDao.saveOrUpdateBatch(null);
        acnDao.saveOrUpdateBatch(Collections.emptyList());

        verifyNoInteractions(sessionFactory);
    }

    @Test
    @DisplayName("Should merge entities, flush every 50 items, and commit transaction on first attempt")
    void saveOrUpdateBatch_ValidBatch_CommitsAndFlushesPeriodically() {
        List<ACN_Entity> entities = new ArrayList<>();
        for (int i = 1; i <= 55; i++) {
            entities.add(new ACN_Entity(new ACN_PK(202610, i), 10.0 * i, 100.0 * i));
        }

        acnDao.saveOrUpdateBatch(entities);

        verify(session, times(55)).merge(any(ACN_Entity.class));
        // Once at 50 items + once final flush before commit
        verify(session, times(2)).flush();
        verify(session, times(2)).clear();
        verify(transaction, times(1)).commit();
        assertTrue(recordedDelays.isEmpty());
    }

    @Test
    @DisplayName("Should retry with jittered exponential backoff on transient JDBCConnectionException and succeed")
    void saveOrUpdateBatch_TransientConnectionFailure_RetriesAndSucceeds() {
        List<ACN_Entity> entities = List.of(new ACN_Entity(new ACN_PK(202610, 101), 50.0, 500.0));

        JDBCConnectionException transientError = new JDBCConnectionException(
                "Connection reset by peer", new SQLException("Link failure", "08S01"));

        // Fail first 2 attempts on commit, succeed on 3rd attempt
        doThrow(transientError)
                .doThrow(transientError)
                .doNothing()
                .when(transaction).commit();

        acnDao.saveOrUpdateBatch(entities);

        verify(sessionFactory, times(3)).openSession();
        verify(transaction, times(2)).rollback();
        verify(transaction, times(3)).commit();

        assertEquals(2, recordedDelays.size());
        // Attempt 1 base = 1000ms (+/- 50% jitter ->)
        assertTrue(recordedDelays.get(0) >= 500L && recordedDelays.get(0) <= 1500L);
        // Attempt 2 base = 2000ms (+/- 50% jitter ->)
        assertTrue(recordedDelays.get(1) >= 1000L && recordedDelays.get(1) <= 3000L);
    }

    @Test
    @DisplayName("Should retry on MS SQL deadlock (LockAcquisitionException) and recover")
    void saveOrUpdateBatch_SqlServerDeadlock_RetriesAndRecovers() {
        List<ACN_Entity> entities = List.of(new ACN_Entity(new ACN_PK(202610, 202), 75.0, 750.0));

        LockAcquisitionException deadlockEx = new LockAcquisitionException(
                "Transaction was deadlocked on lock resources",
                new SQLException("Deadlock victim", "40001", 1205));

        doThrow(deadlockEx)
                .doNothing()
                .when(transaction).commit();

        acnDao.saveOrUpdateBatch(entities);

        verify(sessionFactory, times(2)).openSession();
        verify(transaction, times(1)).rollback();
        assertEquals(1, recordedDelays.size());
    }

    @Test
    @DisplayName("Should fail immediately without retries on non-transient ConstraintViolationException")
    void saveOrUpdateBatch_ConstraintViolation_FailsFastWithoutRetries() {
        List<ACN_Entity> entities = List.of(new ACN_Entity(new ACN_PK(202610, 303), 10.0, 20.0));

        ConstraintViolationException fatalError = new ConstraintViolationException(
                "Cannot insert NULL into column", new SQLException("Constraint failed", "23000", 515), "PK_ACN");

        doThrow(fatalError).when(transaction).commit();

        ConstraintViolationException thrown = assertThrows(
                ConstraintViolationException.class,
                () -> acnDao.saveOrUpdateBatch(entities)
        );

        assertSame(fatalError, thrown);
        verify(sessionFactory, times(1)).openSession();
        verify(transaction, times(1)).rollback();
        assertTrue(recordedDelays.isEmpty());
    }

    @Test
    @DisplayName("Should abort retries and rethrow exception when max_timeout_ms budget is exhausted")
    void saveOrUpdateBatch_BudgetExhausted_AbortsAndThrows() {
        config.setInitialTimeoutMs(50L);
        config.setMaxTimeoutMs(80L);

        // Use real Thread.sleep so elapsed wall-clock time actually exceeds 80ms budget
        ACN_DAO timedDao = new ACN_DAO(sessionFactory, config);
        List<ACN_Entity> entities = List.of(new ACN_Entity(new ACN_PK(202610, 404), 10.0, 20.0));

        JDBCConnectionException persistentOutage = new JDBCConnectionException(
                "MS SQL Server unreachable", new SQLException("Connection refused", "08001"));

        when(sessionFactory.openSession()).thenThrow(persistentOutage);

        assertThrows(JDBCConnectionException.class, () -> timedDao.saveOrUpdateBatch(entities));
    }

    @Test
    @DisplayName("Should accurately classify transient vs permanent SQL exceptions")
    void isTransientDatabaseException_ClassifiesCorrectly() {
        assertTrue(acnDao.isTransientDatabaseException(new RuntimeException(new SQLException("Deadlock", "40001", 1205))));
        assertTrue(acnDao.isTransientDatabaseException(new RuntimeException(new SQLException("Comm link", "08S01", 0))));
        assertFalse(acnDao.isTransientDatabaseException(new RuntimeException(new SQLException("Syntax error", "42000", 102))));
    }
}
