package rs.banka4.bank_service.exceptions.transaction;

import org.springframework.http.HttpStatus;
import rs.banka4.rafeisen.common.exceptions.BaseApiException;

public class ExceededDailyLimit extends BaseApiException {
    public ExceededDailyLimit() {
        super(HttpStatus.FORBIDDEN, null);
    }
}
