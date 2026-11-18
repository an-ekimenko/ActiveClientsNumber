package acn;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;

import acn.network.ACN_HttpClient;
import acn.utils.LogInitializer;
import acn.utils.ResultFileWriter;

/**
 * Main entry point for the Active Clients Number (ACN) ETL staging application.
 */
public class MainLauncher {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;

    public static void main(String[] args) {
        // 1. Bootstrap Log4j2 and P6Spy paths before any logger is instantiated
        LogInitializer.init();
        Logger logger = LogManager.getLogger(MainLauncher.class);

        int exitCode = EXIT_FAILURE;
        int recordsProcessed = 0;
        Injector injector = null;

        try {
            logger.info("Bootstrapping Google Guice DI container for ACN...");
            injector = Guice.createInjector(new ACNAppModule());

            ACN_App app = injector.getInstance(ACN_App.class);
            recordsProcessed = app.run();
            exitCode = EXIT_SUCCESS;

        } catch (Exception e) {
            logger.fatal("ACN application execution terminated with a critical error.", e);
            exitCode = EXIT_FAILURE;
        } finally {
            // 2. Gracefully release HTTP connection pool and Hibernate SessionFactory
            if (injector != null) {
                try {
                    ACN_HttpClient httpClient = injector.getInstance(ACN_HttpClient.class);
                    httpClient.close();
                } catch (Exception e) {
                    logger.warn("Error while closing ACN_HttpClient during shutdown.", e);
                }

                try {
                    SessionFactory sessionFactory = injector.getInstance(SessionFactory.class);
                    if (sessionFactory != null && !sessionFactory.isClosed()) {
                        sessionFactory.close();
                        logger.info("Hibernate SessionFactory closed successfully.");
                    }
                } catch (Exception e) {
                    logger.warn("Error while closing Hibernate SessionFactory during shutdown.", e);
                }
            }

            // 3. Write machine-readable execution summary to exchange/result.yaml
            try {
                ResultFileWriter.writeResult(exitCode, recordsProcessed);
            } catch (Exception e) {
                logger.error("Failed to write execution result summary file.", e);
                exitCode = EXIT_FAILURE;
            }
        }

        logger.info("Exiting ACN process with status code: {}", exitCode);
        System.exit(exitCode);
    }
}
