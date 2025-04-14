package rs.banka4.bank_service.domain.company.db;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;
import rs.banka4.bank_service.domain.user.client.db.Client;

@Entity
@AllArgsConstructor
@Getter
@Setter
@RequiredArgsConstructor
@Builder
@Table(name = "companies")
public class Company {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(
        nullable = false,
        unique = true
    )
    private String name;

    @Column(
        nullable = false,
        unique = true
    )
    private String tin; // PIB

    @Column(
        nullable = false,
        unique = true
    )
    private String crn; // Maticni Broj

    @Column(nullable = false)
    private String address;

    @ManyToOne
    private ActivityCode activityCode;

    @ManyToOne
    private Client majorityOwner;

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
        Company company = (Company) o;
        return getId() != null && Objects.equals(getId(), company.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy
            ? ((HibernateProxy) this).getHibernateLazyInitializer()
                .getPersistentClass()
                .hashCode()
            : getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Company{" + "id=" + id + ", name='" + name + '\'' + '}';
    }
}
