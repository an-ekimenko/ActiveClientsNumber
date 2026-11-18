package acn;

import java.io.File;
import java.io.FileNotFoundException;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import acn.config.ACNApiConfig;
import acn.config.ConfigPathResolver;
import acn.config.YAMLConfigLoader;
import acn.jackson.ACN_DataDeserializer;
import acn.model.ACN_Data;
import acn.model.ACN_Entity;
import acn.network.ExponentialBackoffRetryStrategy;

/**
 * Guice module responsible for configuring core ACN application infrastructure dependencies.
 */
public class ACNAppModule extends AbstractModule {

    private static final Logger logger = LogManager.getLogger(ACNAppModule.class);

    @Provides
    @Singleton
    public ACNApiConfig provideConfig() {
        // Load application properties from the external YAML file at startup
        return YAMLConfigLoader.load("properties.yaml");
    }

    @Provides
    @Singleton
    public ObjectMapper provideObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Register the custom deserializer for ACN_Data mapping
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ACN_Data.class, new ACN_DataDeserializer());
        mapper.registerModule(module);

        return mapper;
    }

    @Provides
    @Singleton
    public HttpClientBuilder provideHttpClientBuilder(ACNApiConfig config) {
        // Attach custom jittered exponential backoff retry strategy with time budget enforcement
        ExponentialBackoffRetryStrategy retryStrategy = new ExponentialBackoffRetryStrategy(config);

        // Timeouts and SSL components are attached lazily by ACN_HttpClient
        return HttpClients.custom()
                .setRetryStrategy(retryStrategy);
    }

    @Provides
    @Singleton
    public SessionFactory provideSessionFactory() throws FileNotFoundException {
        logger.info("Initializing Hibernate SessionFactory infrastructure for ACN...");
        StandardServiceRegistry serviceRegistry = null;

        try {
            // Dynamically locate the external Hibernate configuration file
            File externalFile = ConfigPathResolver.locateConfigFile("Hibernate.cfg.xml");

            Configuration configuration = new Configuration();
            configuration.configure(externalFile);

            logger.info("Registering annotated ACN domain entity models dynamically...");
            configuration.addAnnotatedClass(ACN_Entity.class);

            logger.info("Building production ServiceRegistry container...");
            serviceRegistry = new StandardServiceRegistryBuilder()
                    .applySettings(configuration.getProperties())
                    .build();

            logger.info("MS SQL metadata loaded. Building SessionFactory connection pool...");
            return configuration.buildSessionFactory(serviceRegistry);

        } catch (Exception e) {
            logger.fatal("Database sub-system initialization failed due to configuration error.", e);

            if (serviceRegistry != null) {
                StandardServiceRegistryBuilder.destroy(serviceRegistry);
            }

            throw new RuntimeException("Fatal error during Hibernate architecture startup", e);
        }
    }
}
