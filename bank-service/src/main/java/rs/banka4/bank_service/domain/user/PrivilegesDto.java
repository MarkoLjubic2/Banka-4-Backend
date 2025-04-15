package rs.banka4.bank_service.domain.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "List of privileges DTO response")
public record PrivilegesDto(
    @JsonProperty("privileges")
    @Schema(
        description = "List of employee privileges",
        example = "[\"ADMIN\"]"
    ) List<String> privileges
) {
}
