package com.omniforge.core.persistence;

import com.omniforge.core.persistence.entity.Message;
import com.omniforge.core.persistence.entity.Session;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对话历史会话服务集成测试（SQLite :memory:，问题一：历史列表侧栏数据层）。 */
class ChatSessionServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PersistenceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withPropertyValues("omniforge.persistence.database-file=:memory:");

    @Test
    void 首轮创建会话并逐轮追加() {
        contextRunner.run(context -> {
            ChatSessionService service = context.getBean(ChatSessionService.class);
            SessionRepository sessionRepository = context.getBean(SessionRepository.class);
            MessageRepository messageRepository = context.getBean(MessageRepository.class);

            String id1 = service.appendTurn(null, "你好，介绍一下你自己",
                    "你好！我是 OmniForge 助手。", "deepseek-chat");
            String id2 = service.appendTurn(id1, "你会什么？",
                    "我可以对话、辩论、调用工具。", "deepseek-chat");

            // 同一会话续写：仅 1 个会话、4 条消息
            assertEquals(id1, id2);
            assertEquals(1, sessionRepository.count());
            assertEquals(4, messageRepository.count());
            // 标题 = 首条用户消息前 30 字
            assertEquals("你好，介绍一下你自己", service.list().get(0).name());
            assertEquals(Session.MODE_SINGLE, sessionRepository.findById(id1).orElseThrow().getMode());
        });
    }

    @Test
    void 重命名删除与导出() {
        contextRunner.run(context -> {
            ChatSessionService service = context.getBean(ChatSessionService.class);
            MessageRepository messageRepository = context.getBean(MessageRepository.class);

            String id = service.appendTurn(null, "第一轮", "回复一", "m1");
            service.appendTurn(id, "第二轮", "回复二", "m1");

            service.rename(id, "新名字");
            assertEquals("新名字", service.list().get(0).name());

            String markdown = service.exportMarkdown(id);
            assertTrue(markdown.contains("# 新名字"));
            assertTrue(markdown.contains("第一轮"));
            assertTrue(markdown.contains("回复二"));

            service.delete(id);
            assertTrue(service.list().isEmpty());
            assertEquals(0, messageRepository.count());
        });
    }

    @Test
    void 列表按最后活动排序且消息计数正确() throws Exception {
        contextRunner.run(context -> {
            ChatSessionService service = context.getBean(ChatSessionService.class);

            String a = service.appendTurn(null, "会话A第一轮", "a1", "m1");
            String b = service.appendTurn(null, "会话B第一轮", "b1", "m1");
            // 数据库时间戳为毫秒级：间隔数毫秒再追加 B，确保 B 的最后活动严格晚于 A，
            // 否则两个会话同一毫秒内完成时"按最后活动倒序"顺序不唯一（曾导致偶发失败）
            Thread.sleep(20);
            service.appendTurn(b, "会话B第二轮", "b2", "m1");

            List<ChatSessionService.ChatSessionInfo> list = service.list();
            assertEquals(2, list.size());
            // 会话 B 最后活动在前（两轮 = 4 条消息）
            assertEquals(b, list.get(0).id());
            assertEquals(4, list.get(0).messageCount());
            assertEquals(a, list.get(1).id());
            assertEquals(2, list.get(1).messageCount());
        });
    }
}
