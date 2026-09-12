package com.omniforge.core.persistence;

import com.omniforge.core.persistence.entity.ImMessageDedup;
import com.omniforge.core.persistence.entity.KnowledgeBase;
import com.omniforge.core.persistence.entity.License;
import com.omniforge.core.persistence.entity.Message;
import com.omniforge.core.persistence.entity.Session;
import com.omniforge.core.persistence.entity.SessionModels;
import com.omniforge.core.persistence.entity.ToolCallLog;
import com.omniforge.core.persistence.entity.VectorChunk;
import com.omniforge.core.persistence.entity.Workspace;
import com.omniforge.core.persistence.repository.ImMessageDedupRepository;
import com.omniforge.core.persistence.repository.KnowledgeBaseRepository;
import com.omniforge.core.persistence.repository.LicenseRepository;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionModelsRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.repository.ToolCallLogRepository;
import com.omniforge.core.persistence.repository.VectorChunkRepository;
import com.omniforge.core.persistence.repository.WorkspaceRepository;
import com.omniforge.core.persistence.service.ImMessageDedupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 持久化层集成测试：SQLite :memory:（共享缓存单连接）建表 + 9 实体 CRUD + IM 幂等流程。
 */
class PersistenceIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PersistenceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withPropertyValues("omniforge.persistence.database-file=:memory:");

    @Test
    void 内存库建表并完成九实体CRUD() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context).hasSingleBean(EntityManagerFactory.class);
            assertThat(context).hasSingleBean(WorkspaceRepository.class);
            assertThat(context).hasSingleBean(SessionRepository.class);
            assertThat(context).hasSingleBean(MessageRepository.class);
            assertThat(context).hasSingleBean(ToolCallLogRepository.class);
            assertThat(context).hasSingleBean(KnowledgeBaseRepository.class);
            assertThat(context).hasSingleBean(VectorChunkRepository.class);
            assertThat(context).hasSingleBean(LicenseRepository.class);
            assertThat(context).hasSingleBean(ImMessageDedupRepository.class);
            assertThat(context).hasSingleBean(SessionModelsRepository.class);

            EntityManager em = context.getBean(EntityManagerFactory.class).createEntityManager();
            try {
                em.getTransaction().begin();
                Workspace workspace = new Workspace("工作");
                em.persist(workspace);
                Session session = new Session(workspace, "辩论一", Session.MODE_DEBATE_FREE, 5);
                em.persist(session);
                Message message = new Message(session, "qwen-plus", Message.ROLE_USER, "你好", 10);
                em.persist(message);
                em.persist(new ToolCallLog(session, message, "web_search", "{}", "结果",
                        ToolCallLog.STATUS_SUCCESS, 120));
                KnowledgeBase kb = new KnowledgeBase(workspace, "资料库");
                em.persist(kb);
                em.persist(new VectorChunk(kb, "a.pdf", 0, "片段内容", 100, "row-1"));
                em.persist(new License("key-123", true));
                em.persist(new SessionModels(session, "qwen-plus", SessionModels.ROLE_DEBATER, 1));
                em.persist(new SessionModels(session, "qwen-max", SessionModels.ROLE_DEBATER, 2));
                em.persist(new ImMessageDedup("msg-1", ImMessageDedup.PLATFORM_EMAIL));
                em.getTransaction().commit();

                assertEquals(1, count(em, "select count(w) from Workspace w"));
                assertEquals(1, count(em, "select count(s) from Session s"));
                assertEquals(1, count(em, "select count(m) from Message m"));
                assertEquals(1, count(em, "select count(t) from ToolCallLog t"));
                assertEquals(1, count(em, "select count(k) from KnowledgeBase k"));
                assertEquals(1, count(em, "select count(v) from VectorChunk v"));
                assertEquals(1, count(em, "select count(l) from License l"));
                assertEquals(2, count(em, "select count(sm) from SessionModels sm"));
                assertEquals(1, count(em, "select count(d) from ImMessageDedup d"));
            } finally {
                em.close();
            }
        });
    }

    @Test
    void IM幂等去重完整流程() {
        contextRunner.run(context -> {
            ImMessageDedupService service = context.getBean(ImMessageDedupService.class);
            ImMessageDedupRepository repository = context.getBean(ImMessageDedupRepository.class);

            // 新消息 → 入队
            assertEquals(ImMessageDedupService.Decision.NEW, service.check("email", "m1"));
            assertEquals(1, repository.count(), "首次 check 后应只有一条去重记录");
            // 服务层显式建立的复合唯一索引应存在（SQLiteDialect 不渲染 @Table 唯一约束）
            EntityManager em = context.getBean(EntityManagerFactory.class).createEntityManager();
            try {
                Number indexCount = (Number) em.createNativeQuery(
                                "select count(*) from sqlite_master where type = 'index' "
                                        + "and name = 'uk_im_message_dedup_message_platform'")
                        .getSingleResult();
                assertEquals(1, indexCount.longValue(), "去重表复合唯一索引应由服务层显式创建");
            } finally {
                em.close();
            }
            // 处理中重复到达 → 防重入（唯一约束保证不产生第二行）
            assertEquals(ImMessageDedupService.Decision.IN_PROGRESS, service.check("email", "m1"));
            assertEquals(1, repository.count(), "重复消息不应新增去重记录");
            // 完成 → 永久拒绝
            service.markCompleted("email", "m1", "s-1");
            assertEquals(ImMessageDedupService.Decision.ALREADY_PROCESSED, service.check("email", "m1"));
            // failed → 允许重试
            assertEquals(ImMessageDedupService.Decision.NEW, service.check("email", "m2"));
            service.markFailed("email", "m2");
            assertEquals(ImMessageDedupService.Decision.NEW, service.check("email", "m2"),
                    "failed 后应允许重试入队");
            // 跨平台同 ID 互不影响（v5.2.1 方案 B：复合唯一）
            assertEquals(ImMessageDedupService.Decision.NEW, service.check("dingtalk", "m1"));
            assertEquals(ImMessageDedupService.Decision.IN_PROGRESS, service.check("dingtalk", "m1"));
            assertEquals(3, repository.count(), "去重表应有 email/m1、email/m2、dingtalk/m1 三条记录");
        });
    }

    private static long count(EntityManager em, String jpql) {
        return em.createQuery(jpql, Long.class).getSingleResult();
    }
}
