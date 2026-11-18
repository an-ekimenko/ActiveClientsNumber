package acn;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import acn.config.ACNApiConfig;
import acn.hibernate.ACN_DAO;
import acn.model.ACN;
import acn.model.ACN_Data;
import acn.model.ACN_Entity;
import acn.model.ACN_PK;
import acn.network.ACN_HttpClient;

/**
 * Main application orchestrator coordinating the Active Clients Number (ACN) telemetry data pipeline.
 */
@Singleton
public class ACN_App {

    private static final Logger logger = LogManager.getLogger(ACN_App.class);
    private static final ZoneId MINSK_ZONE = ZoneId.of("Europe/Minsk");

    private final ACNApiConfig config;
    private final ACN_HttpClient httpClient;
    private final ACN_DAO stagingRepository;
    private final ObjectMapper objectMapper;

    @Inject
    public ACN_App(ACNApiConfig config,
                   ACN_HttpClient httpClient,
                   ACN_DAO stagingRepository,
                   ObjectMapper objectMapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.stagingRepository = stagingRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the Active Clients synchronization job: resolve reporting period, fetch JSON,
     * deserialize, deduplicate by composite primary key, and persist in batch.
     *
     * @return the number of unique records successfully persisted to the database
     */
    public int run() {
        logger.info("Initiating Active Clients telemetry integration data pipeline workflow...");

        if (config == null || config.getDataEndpoint() == null || config.getDataEndpoint().isBlank()) {
            throw new IllegalStateException("API data_endpoint URI target configuration is missing for ACN module.");
        }

        // Calculate start and end dates of the previous month based on the Minsk time zone
        LocalDate today = LocalDate.now(MINSK_ZONE);
        LocalDate prevMonth = today.minusMonths(1);
        LocalDate periodBegin = prevMonth.withDayOfMonth(1);
        LocalDate periodEnd = prevMonth.withDayOfMonth(prevMonth.lengthOfMonth());

        // Construct final API endpoint URL using data_endpoint field
        String baseEndpoint = config.getDataEndpoint().trim();
        String separator = baseEndpoint.contains("?") ? "&" : "?";
        String targetUrl = baseEndpoint + separator + "periodbegin=" + periodBegin + "&periodend=" + periodEnd;

        logger.info("Target connection channel context resolved. Destination URI: {}", targetUrl);

        try {
            // Pull raw telemetry payload from target gateway
            String jsonResponse = httpClient.fetchRawJson(targetUrl);
            if (jsonResponse == null || jsonResponse.isBlank()) {
                logger.warn("Pipeline execution aborted: received empty response string.");
                return 0;
            }

            // Deserialize JSON payload into root mapping models
            ACN_Data parsedPayload = objectMapper.readValue(jsonResponse, ACN_Data.class);
            if (parsedPayload == null || parsedPayload.getAcnList() == null || parsedPayload.getAcnList().isEmpty()) {
                logger.warn("Telemetry stream check warning: translated JSON structure is empty.");
                return 0;
            }

            if (parsedPayload.getYYYYMM() == null || parsedPayload.getYYYYMM().isBlank()) {
                throw new IllegalStateException("Reporting period 'dateString' (YYYYMM) is missing in API response.");
            }

            int calendarPeriod = Integer.parseInt(parsedPayload.getYYYYMM().trim());
            logger.info("Transforming transient payload entries for reporting interval period: {} [Source count: {}]...",
                    calendarPeriod, parsedPayload.getAcnList().size());

            // Map DTOs into persistent entities and deduplicate by composite primary key (YYYYMM, memberCode)
            Set<ACN_PK> seenKeys = new HashSet<>();
            List<ACN_Entity> databaseEntitiesBatch = parsedPayload.getAcnList().stream()
                    .map(row -> {
                        ACN_PK primaryKey = new ACN_PK(calendarPeriod, row.getMemberCode());
                        return new ACN_Entity(
                                primaryKey,
                                row.getActiveClients_Month(),
                                row.getActiveClients_Year()
                        );
                    })
                    .filter(entity -> {
                        if (!seenKeys.add(entity.getId())) {
                            logger.warn("Duplicate ACN primary key filtered out: {}", entity.getId());
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            if (databaseEntitiesBatch.isEmpty()) {
                logger.warn("All fetched ACN records were filtered out. Database write skipped.");
                return 0;
            }

            // Bulk write entities batch into database persistence storage
            logger.info("Streaming {} unique active client data models into database persistence storage...",
                    databaseEntitiesBatch.size());
            stagingRepository.saveOrUpdateBatch(databaseEntitiesBatch);

            int processedCount = databaseEntitiesBatch.size();
            logger.info("Active Clients volume tracking data integration job finalized successfully - [{} record(s) processed]!",
                    processedCount);
            return processedCount;

        } catch (IOException e) {
            logger.error("Data binding or format reflection error encountered during parsing transaction sequence stream.", e);
            throw new RuntimeException("Failing node mapping transformation layer blockade", e);
        } catch (Exception e) {
            logger.error("Catastrophic network channel breakdown or unhandled boundary thread exception caught inside ACN runtime block.", e);
            throw new RuntimeException("ACN integration run failed", e);
        }
    }
}
