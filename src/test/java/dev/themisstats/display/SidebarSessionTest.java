package dev.themisstats.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar must not send the create packet of its objective twice.
 *
 * <p>The client accepts the creation of an objective only once; a second create packet for the
 * same name makes it throw {@code IllegalArgumentException: An objective with the name
 * 'themisstats_sidebar' already exists!} and disconnect. A 26.3 client does exactly that, so this
 * is a hard requirement and not a nicety.</p>
 */
class SidebarSessionTest {

    @Test
    void theObjectiveIsCreatedOnlyOnce() {
        SidebarSession session = new SidebarSession();
        assertTrue(session.needsObjectiveCreate(), "第一次必须创建目标");
        session.markObjectiveCreated();
        assertFalse(session.needsObjectiveCreate(), "创建过之后不能再创建");
        // 无论内容改多少次，都不能再要求创建。
        for (int i = 0; i < 50; i++) {
            session.rememberSignature("内容" + i);
            assertFalse(session.needsObjectiveCreate(), "内容变化不允许重新创建目标");
        }
    }

    @Test
    void theDisplaySlotIsSetOnTheFirstSend() {
        SidebarSession session = new SidebarSession();
        assertTrue(session.needsDisplay(), "第一次必须设置显示槽");
        session.markDisplayed();
        assertFalse(session.needsDisplay(), "设置过之后不必重复设置");
    }

    @Test
    void theContentIsOnlySentWhenItChanged() {
        SidebarSession session = new SidebarSession();
        assertTrue(session.contentChanged("a|b"), "第一次的内容算变化");
        session.rememberSignature("a|b");
        assertFalse(session.contentChanged("a|b"), "相同内容不算变化");
        assertTrue(session.contentChanged("a|c"), "内容变了算变化");
        session.rememberSignature("a|c");
        assertEquals("a|c", session.lastSignature());
    }
}
