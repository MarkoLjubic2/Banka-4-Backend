package rs.banka4.bank_service.repositories;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.banka4.bank_service.domain.user.client.db.Client;
import rs.banka4.bank_service.domain.user.client.db.ClientContact;

@Repository
public interface ClientContactRepository extends JpaRepository<ClientContact, UUID> {
    @Query("SELECT c FROM ClientContact c WHERE c.deleted = false AND c.client = :client")
    Page<ClientContact> findAllActive(Pageable pageable, @Param("client") Client client);

    boolean existsByAccountNumber(String accountNumber);

    boolean existsByClient(Client client);

    List<ClientContact> findByClient(Client client);
}
