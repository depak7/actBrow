package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.UserEntity;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
	Optional<UserEntity> findByEmail(String email);
	Optional<UserEntity> findByGoogleId(String googleId);
}
