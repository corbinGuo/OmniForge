package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 会话仓库。 */
public interface SessionRepository extends JpaRepository<Session, String> {

    List<Session> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
