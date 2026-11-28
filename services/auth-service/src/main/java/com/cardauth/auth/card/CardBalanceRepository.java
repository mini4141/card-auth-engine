package com.cardauth.auth.card;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardBalanceRepository extends JpaRepository<CardBalance, String> {
    /** SELECT ... FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CardBalance b where b.cardId = :id")
    Optional<CardBalance> findForUpdate(@Param("id") String id);
}
