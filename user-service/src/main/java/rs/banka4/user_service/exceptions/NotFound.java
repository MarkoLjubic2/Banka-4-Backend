package rs.banka4.user_service.exceptions;

import org.springframework.http.HttpStatus;

public class NotFound extends BaseApiException {
    public NotFound() {
        super(HttpStatus.BAD_REQUEST, null);
    }
}
