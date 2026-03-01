package test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jason.architecture.AgArch;
import jason.asSemantics.Agent;
import jason.asSemantics.Circumstance;
import jason.asSemantics.TransitionSystem;
import jason.asSyntax.ASSyntax;
import jason.runtime.Settings;
import jason.util.TraceLogger;
import junit.framework.TestCase;

public class TraceLoggerTest extends TestCase {

    public void testEventTraceSetting() {
        Settings settings = new Settings();
        assertFalse(settings.hasEventTrace());

        settings.setOptions(Map.of(Settings.EVENT_TRACE, "true"));
        assertTrue(settings.hasEventTrace());

        settings.setOptions(Map.of(Settings.EVENT_TRACE, "false"));
        assertFalse(settings.hasEventTrace());
    }

    public void testTraceFormatEscapes() {
        String msg = TraceLogger.format(TraceLogger.TraceEventType.BELIEF_ADD,
                                        TraceLogger.field("literal", "a;b=c\nx"));
        assertEquals("JASON_TRACE;type=belief_add;literal=a\\;b\\=c\\nx", msg);
    }

    public void testBeliefTraceIsLogged() throws Exception {
        Agent ag = new Agent();
        ag.initAg();

        Settings settings = new Settings();
        settings.setEventTrace(true);

        TransitionSystem ts = new TransitionSystem(ag, new Circumstance(), settings, new AgArch());
        CollectingHandler handler = new CollectingHandler();
        Logger logger = ts.getLogger();
        boolean oldParentHandlers = logger.getUseParentHandlers();

        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            ag.addBel(ASSyntax.parseLiteral("p(a)"));
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(oldParentHandlers);
        }

        assertTrue(handler.contains("type=belief_add"));
        assertTrue(handler.contains("type=event_created"));
    }

    private static class CollectingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        private CollectingHandler() {
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private boolean contains(String text) {
            for (String message: messages) {
                if (message.contains(text)) {
                    return true;
                }
            }
            return false;
        }
    }
}
