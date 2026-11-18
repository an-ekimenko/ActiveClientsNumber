package acn.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object (DTO) modeling the execution summary written to exchange/result.yaml.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {

    @JsonProperty("datetime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime datetime;

    @JsonProperty("returned_code")
    private int returnedCode;

    @JsonProperty("records_processed")
    private int recordsProcessed;
}
