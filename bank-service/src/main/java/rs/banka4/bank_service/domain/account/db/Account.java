package rs.banka4.bank_service.domain.account.db;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;
import rs.banka4.bank_service.domain.company.db.Company;
import rs.banka4.bank_service.domain.user.client.db.Client;
import rs.banka4.bank_service.domain.user.employee.db.Employee;
import rs.banka4.rafeisen.common.currency.CurrencyCode;

@Entity
@Builder
@AllArgsConstructor
@Getter
@Setter
@RequiredArgsConstructor
@Table(name = "accounts")
public class Account {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(
        nullable = false,
        unique = true
    )
    private String accountNumber;

    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    // Balance that is available for withdrawal
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal accountMaintenance = BigDecimal.ZERO;

    @Builder.Default
    private LocalDate createdDate = LocalDate.now();

    @Builder.Default
    private LocalDate expirationDate =
        LocalDate.now()
            .plusYears(100);

    // When client is deleted, it must be set to false
    @Column(nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Builder.Default
    private BigDecimal dailyLimit = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal monthlyLimit = BigDecimal.ZERO;

    @ManyToOne
    private Employee employee;

    @ManyToOne
    private Client client;

    @ManyToOne
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        columnDefinition = "currency"
    )
    private CurrencyCode currency;

    @PrePersist
    public void setAccountMaintenance() {
        if (this.currency != null && CurrencyCode.RSD.equals(this.getCurrency())) {
            setAccountMaintenance(new BigDecimal("100.00"));
        }
    }

    @Override
    public String toString() {
        return "Account{" + "id=" + id + ", accountNumber='" + accountNumber + '\'' + '}';
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass =
            o instanceof HibernateProxy
                ? ((HibernateProxy) o).getHibernateLazyInitializer()
                    .getPersistentClass()
                : o.getClass();
        Class<?> thisEffectiveClass =
            this instanceof HibernateProxy
                ? ((HibernateProxy) this).getHibernateLazyInitializer()
                    .getPersistentClass()
                : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        Account account = (Account) o;
        return getId() != null && Objects.equals(getId(), account.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy
            ? ((HibernateProxy) this).getHibernateLazyInitializer()
                .getPersistentClass()
                .hashCode()
            : getClass().hashCode();
    }
}
