package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.License;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 授权信息仓库。 */
public interface LicenseRepository extends JpaRepository<License, String> {

    Optional<License> findTopByOrderByActivatedAtDesc();
}
