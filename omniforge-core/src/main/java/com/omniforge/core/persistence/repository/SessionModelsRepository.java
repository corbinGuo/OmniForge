package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.SessionModels;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 辩论参与模型列表仓库（v5.1 决议 #6）。 */
public interface SessionModelsRepository extends JpaRepository<SessionModels, String> {

    List<SessionModels> findBySessionIdOrderByDisplayOrderAsc(String sessionId);

    List<SessionModels> findBySessionIdAndRole(String sessionId, String role);
}
