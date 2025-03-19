package rs.banka4.stock_service.domain.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import rs.banka4.stock_service.domain.security.forex.db.ForexPair;
import rs.banka4.stock_service.domain.security.forex.mapper.ForexPairMapper;
import rs.banka4.stock_service.domain.security.future.db.Future;
import rs.banka4.stock_service.domain.security.future.mapper.FutureMapper;
import rs.banka4.stock_service.domain.security.stock.db.Stock;
import rs.banka4.stock_service.domain.security.stock.mapper.StockMapper;

@Component
@RequiredArgsConstructor
public class SecurityMapperImpl {

    private final StockMapper stockMapper;
    private final FutureMapper futureMapper;
    private final ForexPairMapper forexPairMapper;

    public SecurityDto toDto(Security security) {
        if (security instanceof Stock) {
            return new SecurityDto(
                security.getId(),
                security.getName(),
                security.getPrice(),
                stockMapper.toDto((Stock) security),
                null,
                null
            );
        } else if (security instanceof Future) {
            return new SecurityDto(
                security.getId(),
                security.getName(),
                security.getPrice(),
                null,
                futureMapper.toDto((Future) security),
                null
            );
        } else { // security instanceof ForexPair
            return new SecurityDto(
                security.getId(),
                security.getName(),
                security.getPrice(),
                null,
                null,
                forexPairMapper.toDto((ForexPair) security)
            );
        }
    }

    public Security toEntity(SecurityDto securityDto) {
        if (securityDto.stock() != null) {
            return stockMapper.toEntity(securityDto.stock());
        } else if (securityDto.future() != null) {
            return futureMapper.toEntity(securityDto.future());
        } else { // if (securityDto.forexPair() != null){
            ForexPair fp = forexPairMapper.toEntity(securityDto.forexPair());
            fp.setName(fp.getBaseCurrency() + "/" + fp.getQuoteCurrency());
            return fp;
        }
    }
}
