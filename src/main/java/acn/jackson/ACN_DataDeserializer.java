package acn.jackson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import acn.model.ACN;
import acn.model.ACN_Data;

/**
 * Fault-tolerant Jackson deserializer responsible for parsing and transforming raw JSON payloads
 * received from telemetry endpoints into corporate {@link ACN_Data} domain models.
 */
public class ACN_DataDeserializer extends StdDeserializer<ACN_Data> {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(ACN_DataDeserializer.class);

    // Matches integers, floating-point numbers (dot or comma), and scientific notation (e.g., 1e500, 5.24e+1)
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("-?\\d+([.,]\\d+)?([eE][+-]?\\d+)?");

    // Matches consecutive sequences of numeric digits
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    public ACN_DataDeserializer() {
        super(ACN_Data.class);
    }

    public ACN_DataDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public ACN_Data deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
        logger.debug("Executing custom streaming deserialization sequence for incoming ACN network data object...");

        JsonNode rootNode = jp.getCodec().readTree(jp);
        ACN_Data acnData = new ACN_Data();

        if (rootNode == null || !rootNode.isObject()) {
            logger.warn("Expected a JSON object for ACN_Data, but received: {}", rootNode);
            acnData.setAcnList(new ArrayList<>());
            return acnData;
        }

        // 1. Extract the global reporting date string identifier token (e.g., YYYYMM)
        JsonNode dateStringNode = rootNode.path("dateString");
        if (!dateStringNode.isMissingNode() && !dateStringNode.isNull() && dateStringNode.isValueNode()) {
            String rawDate = dateStringNode.asText().trim();
            if (!rawDate.isEmpty()) {
                acnData.setYYYYMM(rawDate);
                logger.debug("Successfully mapped current payload reporting timeline interval: {}", acnData.getYYYYMM());
            }
        } else {
            logger.warn("Payload structure variance: 'dateString' root node is missing or null.");
        }

        // 2. Extract and parse the underlying array holding active client metrics data
        JsonNode acliveClientsNode = rootNode.path("acliveClients");
        ArrayList<ACN> acnList = new ArrayList<>();

        if (acliveClientsNode.isArray()) {
            for (JsonNode element : acliveClientsNode) {
                if (element == null || !element.isObject()) {
                    logger.warn("Skipping non-object element inside 'acliveClients' array: {}", element);
                    continue;
                }

                Integer memberCode = extractMemberCode(element.path("memberCode"));
                if (memberCode == null) {
                    logger.warn("Skipping ACN record with missing or invalid 'memberCode': {}", element);
                    continue;
                }

                ACN record = new ACN();
                record.setMemberCode(memberCode);
                record.setActiveClients_Month(extractMetricDouble(element.path("activeClients_Month")));
                record.setActiveClients_Year(extractMetricDouble(element.path("activeClients_Year")));

                acnList.add(record);
            }
            logger.info("Custom stream binding finalized: extracted {} valid active client items from payload array.", acnList.size());
        } else {
            logger.warn("Payload verification criteria failed: 'acliveClients' JSON array node is empty or missing.");
        }

        acnData.setAcnList(acnList);
        return acnData;
    }

    /**
     * Safely extracts and validates a positive 32-bit integer memberCode from a JSON node.
     */
    private Integer extractMemberCode(JsonNode codeNode) {
        if (codeNode == null || codeNode.isMissingNode() || codeNode.isNull()) {
            return null;
        }
        if (codeNode.isNumber()) {
            if (codeNode.canConvertToInt()) {
                int rawCode = codeNode.asInt();
                if (rawCode == Integer.MIN_VALUE) {
                    logger.warn("Numeric memberCode equals Integer.MIN_VALUE and cannot be negated safely: {}", rawCode);
                    return null;
                }
                return Math.abs(rawCode);
            }
            logger.warn("Numeric memberCode exceeds Integer range limits: {}", codeNode.asText());
            return null;
        }
        if (codeNode.isValueNode()) {
            return parseIntSafe(codeNode.asText());
        }
        return null;
    }

    /**
     * Safely extracts a finite double metric from a JSON node, falling back to 0.0 on invalid or non-finite values.
     */
    private double extractMetricDouble(JsonNode metricNode) {
        if (metricNode == null || metricNode.isMissingNode() || metricNode.isNull()) {
            return 0.0;
        }
        if (metricNode.isNumber()) {
            double val = metricNode.asDouble();
            if (!Double.isFinite(val)) {
                logger.warn("Numeric metric is non-finite (NaN/Infinity). Falling back to 0.0");
                return 0.0;
            }
            return val;
        }
        if (metricNode.isValueNode()) {
            return parseSafeDouble(metricNode.asText());
        }
        return 0.0;
    }

    /**
     * Safely parses an Integer from a raw string with overflow and Integer.MIN_VALUE protection.
     */
    private Integer parseIntSafe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String trimmed = value.trim();
        try {
            long parsedLong = Long.parseLong(trimmed);
            if (Math.abs(parsedLong) > Integer.MAX_VALUE) {
                logger.warn("Parsed memberCode exceeds Integer range limits: '{}'", value);
                return null;
            }
            return (int) Math.abs(parsedLong);
        } catch (NumberFormatException e) {
            Matcher matcher = DIGIT_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                try {
                    long extractedLong = Long.parseLong(matcher.group());
                    if (extractedLong > Integer.MAX_VALUE) {
                        logger.warn("Extracted unsigned memberCode exceeds Integer range: '{}'", matcher.group());
                        return null;
                    }
                    return (int) extractedLong;
                } catch (NumberFormatException ex) {
                    logger.warn("Extracted digit sequence exceeds Long range limits: '{}'", matcher.group());
                    return null;
                }
            }
            logger.warn("Unable to extract a valid positive integer memberCode from string: '{}'", value);
            return null;
        }
    }

    /**
     * Safely parses a raw string input into a finite primitive double.
     * Standardizes comma decimal separators and falls back to 0.0 on invalid or non-finite inputs.
     */
    public static double parseSafeDouble(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0.0;
        }

        String sanitized = rawValue.trim().replaceAll("\\s+", "");
        Matcher matcher = DOUBLE_PATTERN.matcher(sanitized);
        if (!matcher.find()) {
            logger.warn("Payload numeric variance caught: unable to parse '{}' as double. Falling back to 0.0", rawValue);
            return 0.0;
        }

        String normalized = matcher.group().replace(',', '.');
        try {
            double parsed = Double.parseDouble(normalized);
            if (!Double.isFinite(parsed)) {
                logger.warn("Parsed metric is non-finite (Infinity/NaN): '{}' in original value: '{}'. Falling back to 0.0",
                        normalized, rawValue);
                return 0.0;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("Parsed metric exceeds Double range: '{}' in original value: '{}'. Falling back to 0.0",
                    normalized, rawValue);
            return 0.0;
        }
    }
}
