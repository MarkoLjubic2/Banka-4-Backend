package rs.banka4.bank_service.generator;

import java.time.LocalDateTime;
import rs.banka4.bank_service.domain.auth.db.VerificationCode;
import rs.banka4.bank_service.domain.auth.dtos.UserVerificationRequestDto;
import rs.banka4.bank_service.domain.user.employee.db.Employee;

public class AuthObjectMother {

    public static UserVerificationRequestDto generateEmployeeVerificationRequestDto(
        String password,
        String code
    ) {
        return new UserVerificationRequestDto(password, code);
    }

    public static VerificationCode generateVerificationCode(
        String email,
        String code,
        boolean used,
        LocalDateTime expirationDate
    ) {
        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setEmail(email);
        verificationCode.setCode(code);
        verificationCode.setUsed(used);
        verificationCode.setExpirationDate(expirationDate);
        return verificationCode;
    }

    public static Employee generateEmployee(
        String firstName,
        String lastName,
        String email,
        String position
    ) {
        Employee employee = new Employee();
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email);
        employee.setPosition(position);
        return employee;
    }
}
