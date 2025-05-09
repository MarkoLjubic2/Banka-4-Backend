package rs.banka4.bank_service.repositories;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rs.banka4.bank_service.domain.company.db.Company;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {
    boolean existsByTin(String s);

    boolean existsByCrn(String crn);

    boolean existsByName(String name);

    Optional<Company> findByName(String name);

    Optional<Company> findByCrn(String crn);
}
