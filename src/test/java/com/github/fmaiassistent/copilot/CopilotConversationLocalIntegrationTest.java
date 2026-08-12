package com.github.fmaiassistent.copilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
class CopilotConversationLocalIntegrationTest {
    @Test
    void serviceStreamsTextAndCompletesTurn() throws Exception {
        CopilotProperties properties = new CopilotProperties(
                true, "copilot", ".", null, null, null, null, null);
        CopilotConversationService service = new CopilotConversationService(
                properties,
                new CopilotWorkspaceResolver(properties),
                new CopilotExecutableResolver(properties));
        try {
            service.initialize();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!service.availability().ready() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(service.availability().ready()).isTrue();
            var conversation = service.newConversation(null).get(30, TimeUnit.SECONDS);
            var events = new CopyOnWriteArrayList<CopilotEvent>();
            var subscription = service.subscribe(conversation.conversation().sessionId(), events::add);

            String turnId = service.sendMessage(
                    conversation.conversation().sessionId(), "Reply exactly with OK.")
                    .get(30, TimeUnit.SECONDS);
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            while (events.stream().noneMatch(CopilotEvent.TurnCompleted.class::isInstance)
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }

            assertThat(events).anySatisfy(event -> assertThat(event)
                    .isEqualTo(new CopilotEvent.TextDelta(
                            conversation.conversation().sessionId(), turnId, "assistant-" + turnId, "OK")));
            assertThat(events).anyMatch(CopilotEvent.TurnCompleted.class::isInstance);
            subscription.close();
        } finally {
            service.shutdown();
        }
    }
}
