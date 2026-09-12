package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 知识库仓库。 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, String> {

    List<KnowledgeBase> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
