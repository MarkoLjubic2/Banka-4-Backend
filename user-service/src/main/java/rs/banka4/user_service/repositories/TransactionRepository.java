package rs.banka4.user_service.repositories;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rs.banka4.user_service.domain.transaction.db.Transaction;

public interface TransactionRepository extends
    JpaRepository<Transaction, UUID>,
    JpaSpecificationExecutor<Transaction> {
    Optional<Transaction> findByTransactionNumber(String transactionNumber);
}
