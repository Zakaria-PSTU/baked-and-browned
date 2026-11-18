package com.bakedandbrowned.identity.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.identity.entity.Role;
import com.bakedandbrowned.identity.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByPhone(String phone);

	/** Case-insensitive, matching the {@code uq_users_email_ci} index on {@code lower(email)}. */
	@Query("select u from UserAccount u where lower(u.email) = lower(:email)")
	Optional<UserAccount> findByEmailIgnoringCase(@Param("email") String email);

	boolean existsByPhone(String phone);

	@Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(:email)")
	boolean existsByEmailIgnoringCase(@Param("email") String email);

	boolean existsByRole(Role role);

}
