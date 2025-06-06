package rs.banka4.rafeisen.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import rs.banka4.rafeisen.common.security.Privilege;

@Schema(description = "EmployeeResponse details DTO")
public record EmployeeResponseDto(
    @Schema(
        description = "Employee ID",
        example = "1fad2c01-f82f-41a6-822c-8ca1b3232575"
    ) UUID id,

    @Schema(
        description = "First name",
        example = "Mehmedalija"
    ) String firstName,

    @Schema(
        description = "Last name",
        example = "Doe"
    ) String lastName,

    @Schema(
        description = "Date of birth",
        example = "1990-01-01"
    ) LocalDate dateOfBirth,

    @Schema(
        description = "Gender (MALE or FEMALE)",
        example = "MALE"
    ) Gender gender,

    @Schema(
        description = "Email address",
        example = "john.doe@example.com"
    ) String email,

    @Schema(
        description = "Phone number",
        example = "+381671452369"
    ) String phone,

    @Schema(
        description = "Address",
        example = "123 Main St"
    ) String address,

    @Schema(
        description = "Username",
        example = "johndoe"
    ) String username,

    @Schema(
        description = "Job position",
        example = "Developer"
    ) String position,

    @Schema(
        description = "Department",
        example = "IT"
    ) String department,

    @Schema(
        description = "List of privileges",
        example = "[\"ADMIN\"]"
    ) EnumSet<Privilege> privileges,

    @Schema(
        description = "Indicates if the employee is active",
        example = "false"
    ) boolean active
) {
}
