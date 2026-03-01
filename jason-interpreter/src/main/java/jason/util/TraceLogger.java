package jason.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jason.asSemantics.ActionExec;
import jason.asSemantics.Event;
import jason.asSemantics.Intention;
import jason.asSemantics.TransitionSystem;
import jason.asSyntax.Literal;
import jason.asSyntax.Term;
import jason.asSyntax.Trigger;
import jason.runtime.Settings;

public final class TraceLogger {

    public enum TraceEventType {
        CYCLE_START("cycle_start"),
        BELIEF_ADD("belief_add"),
        BELIEF_DEL("belief_del"),
        GOAL_ADD("goal_add"),
        GOAL_FINISHED("goal_finished"),
        GOAL_FAILED("goal_failed"),
        EVENT_CREATED("event_created"),
        EVENT_SELECTED("event_selected"),
        ACTION_SELECTED("action_selected"),
        ACTION_EXECUTED("action_executed");

        private final String wireName;

        TraceEventType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record Field(String key, Object value) { }

    public static final String PREFIX = "JASON_TRACE";
    private static final Path DEFAULT_TRACE_DIR = Paths.get("log", "event-trace");
    private static final DateTimeFormatter RUN_STAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String RUN_STAMP = LocalDateTime.now().format(RUN_STAMP_FORMAT);
    private static final Formatter TRACE_FILE_FORMATTER = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return record.getMessage() + System.lineSeparator();
        }
    };
    private static final Map<String, Logger> TRACE_LOGGERS = new HashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TraceLogger::closeAll, "jason-trace-logger-shutdown"));
    }

    private TraceLogger() {
    }

    public static boolean isEnabled(Settings settings) {
        return settings != null && settings.hasEventTrace();
    }

    public static Integer intentionId(Intention intention) {
        return intention == null ? null : intention.getId();
    }

    public static Field field(String key, Object value) {
        return new Field(key, value);
    }

    public static void cycleStart(TransitionSystem ts, int cycle) {
        log(ts, TraceEventType.CYCLE_START, field("cycle", cycle));
    }

    public static void beliefAdded(TransitionSystem ts, Literal literal, Intention intention) {
        log(ts, TraceEventType.BELIEF_ADD,
            field("literal", literal),
            field("intention", intentionId(intention)));
    }

    public static void beliefRemoved(TransitionSystem ts, Literal literal, Intention intention) {
        log(ts, TraceEventType.BELIEF_DEL,
            field("literal", literal),
            field("intention", intentionId(intention)));
    }

    public static void goalAdded(TransitionSystem ts, Trigger trigger, Intention intention) {
        log(ts, TraceEventType.GOAL_ADD,
            field("trigger", trigger),
            field("intention", intentionId(intention)));
    }

    public static void goalFinished(TransitionSystem ts, Trigger trigger, Intention intention) {
        log(ts, TraceEventType.GOAL_FINISHED,
            field("trigger", trigger),
            field("intention", intentionId(intention)));
    }

    public static void goalFailed(TransitionSystem ts, Trigger trigger, Intention intention, Term reason) {
        log(ts, TraceEventType.GOAL_FAILED,
            field("trigger", trigger),
            field("intention", intentionId(intention)),
            field("reason", reason));
    }

    public static void eventCreated(TransitionSystem ts, Event event) {
        log(ts, TraceEventType.EVENT_CREATED,
            field("trigger", event.getTrigger()),
            field("intention", intentionId(event.getIntention())),
            field("external", event.isExternal()),
            field("internal", event.isInternal()));
    }

    public static void eventSelected(TransitionSystem ts, Event event) {
        log(ts, TraceEventType.EVENT_SELECTED,
            field("trigger", event.getTrigger()),
            field("intention", intentionId(event.getIntention())),
            field("external", event.isExternal()));
    }

    public static void actionSelected(TransitionSystem ts, ActionExec action) {
        log(ts, TraceEventType.ACTION_SELECTED,
            field("action", action.getActionTerm()),
            field("intention", intentionId(action.getIntention())));
    }

    public static void actionExecuted(TransitionSystem ts, ActionExec action) {
        log(ts, TraceEventType.ACTION_EXECUTED,
            field("action", action.getActionTerm()),
            field("intention", intentionId(action.getIntention())),
            field("result", action.getResult()),
            field("failure_reason", action.getFailureReason()),
            field("failure_msg", action.getFailureMsg()));
    }

    public static void log(TransitionSystem ts, TraceEventType type, Field... fields) {
        if (ts == null || !isEnabled(ts.getSettings())) {
            return;
        }

        try {
            getTraceLogger(ts).log(Level.INFO, format(ts, type, fields));
        } catch (IOException e) {
            Logger runtimeLogger = ts.getLogger();
            if (runtimeLogger != null) {
                runtimeLogger.log(Level.WARNING, "Error opening event trace file.", e);
            }
        }
    }

    public static String format(TraceEventType type, Field... fields) {
        return format(null, type, fields);
    }

    public static String format(TransitionSystem ts, TraceEventType type, Field... fields) {
        StringBuilder out = new StringBuilder(PREFIX);
        append(out, "type", type.wireName());

        if (ts != null && ts.getAgArch() != null) {
            append(out, "agent", ts.getAgArch().getAgName());
        }

        if (fields != null) {
            for (Field field: fields) {
                if (field != null) {
                    append(out, field.key(), field.value());
                }
            }
        }

        return out.toString();
    }

    public static synchronized void closeAll() {
        for (Logger logger: TRACE_LOGGERS.values()) {
            for (Handler handler: logger.getHandlers()) {
                handler.flush();
                handler.close();
                logger.removeHandler(handler);
            }
        }
        TRACE_LOGGERS.clear();
    }

    private static synchronized Logger getTraceLogger(TransitionSystem ts) throws IOException {
        Path traceFile = resolveTraceFile(ts);
        String key = traceFile.toAbsolutePath().normalize().toString();

        Logger logger = TRACE_LOGGERS.get(key);
        if (logger != null) {
            return logger;
        }

        Path parent = traceFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        logger = Logger.getLogger("jason.trace." + sanitizeLoggerComponent(key));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);

        for (Handler handler: logger.getHandlers()) {
            handler.close();
            logger.removeHandler(handler);
        }

        FileHandler fileHandler = new FileHandler(traceFile.toString(), true);
        fileHandler.setLevel(Level.INFO);
        fileHandler.setFormatter(TRACE_FILE_FORMATTER);
        logger.addHandler(fileHandler);

        TRACE_LOGGERS.put(key, logger);
        return logger;
    }

    private static Path resolveTraceFile(TransitionSystem ts) {
        String agentName = "agent";
        if (ts.getAgArch() != null) {
            String candidate = ts.getAgArch().getAgName();
            if (candidate != null && !candidate.isBlank()) {
                agentName = candidate;
            }
        }
        return resolveRunDirectory(ts.getSettings()).resolve(sanitizeFileComponent(agentName) + ".eventtrace");
    }

    private static Path resolveRunDirectory(Settings settings) {
        String configuredDir = settings == null ? null : settings.getEventTraceDir();
        Path baseDir;
        if (configuredDir == null || configuredDir.isBlank()) {
            baseDir = DEFAULT_TRACE_DIR;
        } else {
            baseDir = Paths.get(configuredDir);
        }
        return baseDir.resolve(RUN_STAMP);
    }

    private static void append(StringBuilder out, String key, Object value) {
        if (key == null || value == null) {
            return;
        }

        out.append(';')
           .append(escape(key))
           .append('=')
           .append(escape(String.valueOf(value)));
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
            case '\\':
                out.append("\\\\");
                break;
            case ';':
                out.append("\\;");
                break;
            case '=':
                out.append("\\=");
                break;
            case '\n':
                out.append("\\n");
                break;
            case '\r':
                out.append("\\r");
                break;
            default:
                out.append(ch);
                break;
            }
        }
        return out.toString();
    }

    private static String sanitizeFileComponent(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sanitizeLoggerComponent(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
