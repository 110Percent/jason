package test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

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

    public void testBeliefTraceIsLoggedToFile() throws Exception {
        Agent ag = new Agent();
        ag.initAg();

        Path traceDir = Files.createTempDirectory("jason-trace-test");
        Settings settings = new Settings();
        settings.setEventTrace(true);
        settings.addOption(Settings.EVENT_TRACE_DIR, traceDir.toString());

        new TransitionSystem(ag, new Circumstance(), settings, new AgArch());

        try {
            ag.addBel(ASSyntax.parseLiteral("p(a)"));
        } finally {
            TraceLogger.closeAll();
        }

        Path traceFile;
        try (Stream<Path> files = Files.walk(traceDir)) {
            traceFile = files.filter(Files::isRegularFile)
                             .filter(path -> path.getFileName().toString().endsWith(".eventtrace"))
                             .findFirst()
                             .orElseThrow();
        }

        String traceText = Files.readString(traceFile);
        assertTrue(traceText.contains("type=belief_add"));
        assertTrue(traceText.contains("type=event_created"));
        assertTrue(traceFile.getFileName().toString().endsWith(".eventtrace"));

        Path runDir = traceFile.getParent();
        assertEquals(traceDir, runDir.getParent());
        assertTrue(runDir.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}"));
    }
}
