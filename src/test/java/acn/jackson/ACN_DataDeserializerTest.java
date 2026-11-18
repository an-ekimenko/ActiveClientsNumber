package acn.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import acn.model.ACN;
import acn.model.ACN_Data;

class ACN_DataDeserializerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ACN_Data.class, new ACN_DataDeserializer());
        objectMapper.registerModule(module);
    }

    @Test
    @DisplayName("Should correctly deserialize standard valid ACN JSON payload")
    void shouldDeserializeStandardValidPayload() throws Exception {
        String json = """
                {
                  "dateString": "202610",
                  "acliveClients": [
                    {
                      "memberCode": 1001,
                      "activeClients_Month": 15420.5,
                      "activeClients_Year": 184500.75
                    },
                    {
                      "memberCode": 2002,
                      "activeClients_Month": 830.0,
                      "activeClients_Year": 9410.25
                    }
                  ]
                }
                """;

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertNotNull(result);
        assertEquals("202610", result.getYYYYMM());
        assertNotNull(result.getAcnList());
        assertEquals(2, result.getAcnList().size());

        ACN first = result.getAcnList().get(0);
        assertEquals(1001, first.getMemberCode());
        assertEquals(15420.5, first.getActiveClients_Month(), 0.0001);
        assertEquals(184500.75, first.getActiveClients_Year(), 0.0001);

        ACN second = result.getAcnList().get(1);
        assertEquals(2002, second.getMemberCode());
        assertEquals(830.0, second.getActiveClients_Month(), 0.0001);
        assertEquals(9410.25, second.getActiveClients_Year(), 0.0001);
    }

    @ParameterizedTest
    @CsvSource({
            "'-105', 105",
            "'  77  ', 77",
            "'MEMBER-314', 314",
            "'ID-0089', 89",
            "'2147483647', 2147483647",
            "'-2147483647', 2147483647"
    })
    @DisplayName("Should extract positive Integer memberCode from negative or alphanumeric strings")
    void shouldExtractPositiveMemberCodesFromStrings(String rawMemberCode, int expectedCode) throws Exception {
        String json = String.format("""
                {
                  "dateString": "202610",
                  "acliveClients": [
                    { "memberCode": "%s", "activeClients_Month": 10, "activeClients_Year": 20 }
                  ]
                }
                """, rawMemberCode);

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertEquals(1, result.getAcnList().size());
        assertEquals(expectedCode, result.getAcnList().get(0).getMemberCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"2147483648\"",           // Integer.MAX_VALUE + 1 as string
            "\"-2147483648\"",          // Integer.MIN_VALUE as string (Math.abs overflow trap)
            "\"99999999999999999999\"", // Exceeds Long.MAX_VALUE
            "\"no-digits\"",            // Non-numeric text
            "\"   \"",                  // Blank string
            "null",                     // Explicit null
            "999999999999",             // Numeric literal exceeding Integer.MAX_VALUE
            "-2147483648"               // Numeric literal equal to Integer.MIN_VALUE
    })
    @DisplayName("Should skip records when memberCode overflows Integer or is invalid/null")
    void shouldSkipRecordsWithInvalidOrOverflowingMemberCode(String rawMemberCodeJson) throws Exception {
        String json = String.format("""
                {
                  "dateString": "202610",
                  "acliveClients": [
                    { "memberCode": %s, "activeClients_Month": 100.0, "activeClients_Year": 200.0 },
                    { "memberCode": 555, "activeClients_Month": 50.0, "activeClients_Year": 60.0 }
                  ]
                }
                """, rawMemberCodeJson);

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertNotNull(result.getAcnList());
        assertEquals(1, result.getAcnList().size());
        assertEquals(555, result.getAcnList().get(0).getMemberCode());
    }

    @ParameterizedTest
    @CsvSource({
            "'1 250,75', 1250.75",
            "'  99,5  ', 99.5",
            "'1.5e2', 150.0",
            "'-42,25', -42.25"
    })
    @DisplayName("Should normalize comma separators, spaces, and scientific notation in metrics")
    void shouldNormalizeMetricsFormatting(String rawMetric, double expectedValue) throws Exception {
        String json = String.format("""
                {
                  "dateString": "202610",
                  "acliveClients": [
                    {
                      "memberCode": 10,
                      "activeClients_Month": "%s",
                      "activeClients_Year": "%s"
                    }
                  ]
                }
                """, rawMetric, rawMetric);

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertEquals(1, result.getAcnList().size());
        assertEquals(expectedValue, result.getAcnList().get(0).getActiveClients_Month(), 0.0001);
        assertEquals(expectedValue, result.getAcnList().get(0).getActiveClients_Year(), 0.0001);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"N/A\"",
            "\"\"",
            "\"NaN\"",
            "\"Infinity\"",
            "\"-Infinity\"",
            "\"1e500\"", // Overflows Double.MAX_VALUE -> Infinity
            "null"
    })
    @DisplayName("Should safely fall back to 0.0 when metric is invalid, empty, or non-finite")
    void shouldFallbackToZeroOnInvalidOrNonFiniteMetrics(String rawMetricJson) throws Exception {
        String json = String.format("""
                {
                  "dateString": "202610",
                  "acliveClients": [
                    {
                      "memberCode": 10,
                      "activeClients_Month": %s,
                      "activeClients_Year": %s
                    }
                  ]
                }
                """, rawMetricJson, rawMetricJson);

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertEquals(1, result.getAcnList().size());
        assertEquals(0.0, result.getAcnList().get(0).getActiveClients_Month(), 0.0001);
        assertEquals(0.0, result.getAcnList().get(0).getActiveClients_Year(), 0.0001);
    }

    @Test
    @DisplayName("Should safely handle null dateString, null array items, and missing acliveClients")
    void shouldHandleNullFieldsAndMalformedArrayItems() throws Exception {
        String json = """
                {
                  "dateString": null,
                  "acliveClients": [
                    null,
                    "unexpected_string_element",
                    { "activeClients_Month": 12.3 },
                    { "memberCode": 777, "activeClients_Month": 45.6, "activeClients_Year": 89.0 }
                  ]
                }
                """;

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertNotNull(result);
        assertNull(result.getYYYYMM());
        assertNotNull(result.getAcnList());
        assertEquals(1, result.getAcnList().size());
        assertEquals(777, result.getAcnList().get(0).getMemberCode());
        assertEquals(45.6, result.getAcnList().get(0).getActiveClients_Month(), 0.0001);
    }

    @Test
    @DisplayName("Should return empty list when acliveClients is missing or null")
    void shouldReturnEmptyListWhenArrayMissing() throws Exception {
        String json = "{\"dateString\": \"202610\"}";

        ACN_Data result = objectMapper.readValue(json, ACN_Data.class);

        assertNotNull(result);
        assertEquals("202610", result.getYYYYMM());
        assertNotNull(result.getAcnList());
        assertTrue(result.getAcnList().isEmpty());
    }
}
