package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 消息仓库（按时间升序 = 辩论历史重播顺序，需求 4.3）。 */
public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 删除会话时级联清理消息（历史列表删除操作；返回删除条数） */
    long deleteBySessionId(String sessionId);
}
