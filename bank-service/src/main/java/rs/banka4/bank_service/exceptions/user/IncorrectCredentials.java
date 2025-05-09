package rs.banka4.bank_service.exceptions.user;

import org.springframework.http.HttpStatus;
import rs.banka4.rafeisen.common.exceptions.BaseApiException;

public class IncorrectCredentials extends BaseApiException {
    public IncorrectCredentials() {
        super(HttpStatus.UNAUTHORIZED, null);
    }
}
