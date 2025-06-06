package rs.banka4.bank_service.domain.security.forex.dtos;


import com.fasterxml.jackson.annotation.JsonProperty;

public record ForexPairApiDto(
    @JsonProperty(
        "Realtime Currency Exchange Rate"
    ) RealTimeCurrencyExchangeRate realTimeCurrencyExchangeRate
) {

}
