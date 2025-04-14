package rs.banka4.stock_service.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import rs.banka4.stock_service.domain.actuaries.db.MonetaryAmount;
import rs.banka4.stock_service.domain.listing.db.Listing;
import rs.banka4.stock_service.domain.security.Security;

@Repository
public interface ListingRepository extends
    JpaRepository<Listing, UUID>,
    JpaSpecificationExecutor<Listing> {

    @Query(
        value = "select l from Listing l where l.security.id = :securityId order by l.lastRefresh desc"
    )
    Optional<Listing> getLatestListing(UUID securityId, Limit limit);

    Listing findListingBySecurity(Security security);

    Optional<Listing> findBySecurityIdAndActiveTrue(UUID assetId);

    @Query(
        value = "select new rs.banka4.stock_service.domain.actuaries.db.MonetaryAmount(l.ask, l.exchange.currency) from Listing l where l.security.id = :stockId order by l.lastRefresh desc"
    )
    Optional<MonetaryAmount> getLatestStockPrice(UUID stockId, Limit limit);

    @Modifying
    @Transactional
    @Query("UPDATE Listing l SET l.active = false")
    void deactivateAll();

    @Query("SELECT l FROM Listing l WHERE l.active = true")
    List<Listing> findAllActiveListings();
}
