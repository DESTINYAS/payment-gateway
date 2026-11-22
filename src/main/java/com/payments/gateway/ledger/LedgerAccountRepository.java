package com.payments.gateway.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    /** Pessimistic row lock. Callers must lock accounts in a fixed order to avoid deadlocks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.code = :code and a.currency = :currency")
    Optional<LedgerAccount> findForUpdate(@Param("code") String code, @Param("currency") String currency);
}
