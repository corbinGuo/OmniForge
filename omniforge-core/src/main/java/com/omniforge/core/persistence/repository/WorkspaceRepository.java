package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 工作区仓库。 */
public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    Optional<Workspace> findByName(String name);
}
