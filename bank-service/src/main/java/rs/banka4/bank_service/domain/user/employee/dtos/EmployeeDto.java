package rs.banka4.bank_service.domain.user.employee.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import rs.banka4.rafeisen.common.dto.Gender;

@Schema(description = "DTO for returned employees in response")
public record EmployeeDto(
    @Schema(
        description = "Employee's id",
        example = "1de54a3a-d879-4154-aa3a-e40598186f93"
    ) UUID id,

    @Schema(
        description = "Employee's first name",
        example = "Ognjen"
    ) String firstName,

    @Schema(
        description = "Employee's last name",
        example = "Jukic"
    ) String lastName,

    @Schema(
        description = "Employee's date of birth",
        example = "1990-05-15"
    ) LocalDate dateOfBirth,

    @Schema(
        description = "Employee's gender (MALE or FEMALE)",
        example = "MALE"
    ) Gender gender,

    @Schema(
        description = "Employee's email address",
        example = "mljubic9422112rn@raf.rs"
    ) String email,

    @Schema(
        description = "Employee's phoneNumber number",
        example = "+381610123456"
    ) String phone,

    @Schema(
        description = "Employee's address",
        example = "123 Grove Street, City, Country"
    ) String address,

    @Schema(
        description = "Employee's username",
        example = "funfa2c1t"
    ) String username,

    @Schema(
        description = "Employee's position",
        example = "Software Engineer"
    ) String position,

    @Schema(
        description = "Employee's department",
        example = "IT"
    ) String department,

    @Schema(
        description = "Indicates if the employee is active",
        example = "true"
    ) boolean active
) {
}
