package acn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import acn.config.ACNApiConfig;
import acn.hibernate.ACN_DAO;
import acn.jackson.ACN_DataDeserializer;
import acn.model.ACN_Data;
import acn.model.ACN_Entity;
import acn.model.ACN_PK;
import acn.network.ACN_HttpClient;

@ExtendWith(MockitoExtension.class)
class ACN_AppTest {

    private static final String BASE_ENDPOINT = "https://example.com" + "/api/public/activeclients";

    @Mock
    private ACN_HttpClient httpClient;

    @Mock
    private ACN_DAO acnDao;

    @Captor
    private ArgumentCaptor<List<ACN_Entity>> entityListCaptor;

    @Captor
    private ArgumentCaptor<String> urlCaptor;

    private ACNApiConfig config;
    private ACN_App acnApp;

    @BeforeEach
    void setUp() {
        config = new ACNApiConfig();
        config.setDataEndpoint(BASE_ENDPOINT);

        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ACN_Data.class, new ACN_DataDeserializer());
        objectMapper.registerModule(module);

        acnApp = new ACN_App(config, httpClient, acnDao, objectMapper);
    }

    @Test
    @DisplayName("Should build previous-month URL, deduplicate by composite ACN_PK, persist batch, and return count")
    void run_ValidPayloadWithDuplicates_DeduplicatesAndPersistsBatch() throws Exception {
        // Arrange: 3 valid records where memberCode 101 appears twice + 1 invalid record skipped by deserializer
        String sampleJson = """
                {
                  "dateString": "202610",
                  "acliveClients": [
                    { "memberCode": 101, "activeClients_Month": 500.5, "activeClients_Year": 6100.0 },
                    { "memberCode": "101", "activeClients_Month": 999.0, "activeClients_Year": 9999.0 },
                    { "memberCode": null, "activeClients_Month": 10.0, "activeClients_Year": 20.0 },
                    { "memberCode": 202, "activeClients_Month": "1 250,25", "activeClients_Year": "14 800,75" }
                  ]
                }
                """;

        when(httpClient.fetchRawJson(anyString())).thenReturn(sampleJson);

        // Expected previous month bounds in Europe/Minsk
        LocalDate prevMonth = LocalDate.now(ZoneId.of("Europe/Minsk")).minusMonths(1);
        String expectedBegin = prevMonth.withDayOfMonth(1).toString();
        String expectedEnd = prevMonth.withDayOfMonth(prevMonth.lengthOfMonth()).toString();
        String expectedUrl = BASE_ENDPOINT + "?periodbegin=" + expectedBegin + "&periodend=" + expectedEnd;

        // Act
        int processedCount = acnApp.run();

        // Assert: URL parameters match previous calendar month
        verify(httpClient, times(1)).fetchRawJson(urlCaptor.capture());
        assertEquals(expectedUrl, urlCaptor.getValue());

        // Assert: only 2 unique composite keys persisted
        assertEquals(2, processedCount);
        verify(acnDao, times(1)).saveOrUpdateBatch(entityListCaptor.capture());

        List<ACN_Entity> persisted = entityListCaptor.getValue();
        assertEquals(2, persisted.size());

        ACN_Entity first = persisted.get(0);
        assertEquals(new ACN_PK(202610, 101), first.getId());
        assertEquals(500.5, first.getActiveClients_Month(), 0.0001);
        assertEquals(6100.0, first.getActiveClients_Year(), 0.0001);

        ACN_Entity second = persisted.get(1);
        assertEquals(new ACN_PK(202610, 202), second.getId());
        assertEquals(1250.25, second.getActiveClients_Month(), 0.0001);
        assertEquals(14800.75, second.getActiveClients_Year(), 0.0001);
    }

    @Test
    @DisplayName("Should append query params using '&' when data_endpoint already contains '?'")
    void run_EndpointWithExistingQueryParam_AppendsWithAmpersand() throws Exception {
        config.setDataEndpoint(BASE_ENDPOINT + "?format=json");
        when(httpClient.fetchRawJson(anyString())).thenReturn("{\"dateString\":\"202610\",\"acliveClients\":[]}");

        int processedCount = acnApp.run();

        assertEquals(0, processedCount);
        verify(httpClient).fetchRawJson(urlCaptor.capture());
        assertTrue(urlCaptor.getValue().startsWith(BASE_ENDPOINT + "?format=json&periodbegin="));
        verifyNoInteractions(acnDao);
    }

    @Test
    @DisplayName("Should return 0 and skip database persistence when acliveClients array is empty")
    void run_EmptyClientsArray_ReturnsZeroAndSkipsDao() throws Exception {
        when(httpClient.fetchRawJson(anyString())).thenReturn("{\"dateString\":\"202610\",\"acliveClients\":[]}");

        int processedCount = acnApp.run();

        assertEquals(0, processedCount);
        verifyNoInteractions(acnDao);
    }

    @Test
    @DisplayName("Should return 0 and skip database persistence when HTTP client returns blank payload")
    void run_BlankPayload_ReturnsZeroAndSkipsDao() throws Exception {
        when(httpClient.fetchRawJson(anyString())).thenReturn("   ");

        int processedCount = acnApp.run();

        assertEquals(0, processedCount);
        verifyNoInteractions(acnDao);
    }

    @Test
    @DisplayName("Should throw RuntimeException when dateString (YYYYMM) is missing in non-empty payload")
    void run_MissingDateString_ThrowsRuntimeException() throws Exception {
        String jsonWithoutDate = "{\"acliveClients\":[{\"memberCode\":101,\"activeClients_Month\":10,\"activeClients_Year\":20}]}";
        when(httpClient.fetchRawJson(anyString())).thenReturn(jsonWithoutDate);

        assertThrows(RuntimeException.class, () -> acnApp.run());
        verifyNoInteractions(acnDao);
    }

    @Test
    @DisplayName("Should throw IllegalStateException immediately when data_endpoint is missing")
    void run_MissingDataEndpoint_ThrowsIllegalStateException() {
        config.setDataEndpoint("  ");

        assertThrows(IllegalStateException.class, () -> acnApp.run());
        verifyNoInteractions(httpClient, acnDao);
    }

    @Test
    @DisplayName("Should propagate RuntimeException when HTTP client fails")
    void run_HttpClientThrowsException_WrapsAndThrowsRuntimeException() throws Exception {
        when(httpClient.fetchRawJson(anyString())).thenThrow(new RuntimeException("Gateway timeout"));

        assertThrows(RuntimeException.class, () -> acnApp.run());
        verifyNoInteractions(acnDao);
    }
}
