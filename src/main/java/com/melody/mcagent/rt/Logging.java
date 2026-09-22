package com.melody.mcagent.rt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.TimeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends every mcagent log line to its own file instead of the server console.
 *
 * <p>The bot talks a lot - every tool call, every decision, every repair - and on a server with
 * players on it that buries everything else in the console, including the other mods' warnings. Its
 * own file also survives the way a console scroll does not: the whole point of these lines is that
 * somebody reads them later to find out why a bot did something.
 *
 * <p>Done from the runtime rather than from a {@code log4j2.xml} in the server root, for two
 * reasons. The server already has a working log configuration and replacing it would change how
 * <em>every</em> mod logs; and this way the routing ships with the runtime jar, so it can be changed
 * with {@code /mcagent reload} like everything else here.
 *
 * <p>{@code additivity=false} on the {@code mcagent} logger is what keeps the console clean: every
 * logger in this mod is named {@code mcagent/...} and inherits from it. Set
 * {@code MCAGENT_LOG_CONSOLE=true} to have both.
 */
public final class Logging {

    /** Every logger in the mod is named under this one, so one entry covers all of them. */
    private static final String ROOT_LOGGER = "mcagent";
    private static final String APPENDER_NAME = "mcagent-file";

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/runtime");

    private Logging() {
    }

    /**
     * Route this mod's logs to {@code logs/mcagent.log} (or {@code MCAGENT_LOG_FILE}).
     *
     * <p>Safe to call on every load: an appender that is already installed is reused rather than
     * added again, so a reload does not stack up appenders and multiply every line.
     */
    public static void routeToOwnFile() {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            if (config.getAppenders().containsKey(APPENDER_NAME)) {
                return;
            }
            Path file = logFile();
            RollingFileAppender appender = RollingFileAppender.newBuilder()
                    .setName(APPENDER_NAME)
                    .withFileName(file.toString())
                    // Same shape as the server's own lines, so a pasted log line still says when.
                    .setLayout(PatternLayout.newBuilder()
                            .withPattern("[%d{ddMMM yyyy HH:mm:ss.SSS}] [%t/%level] [%logger]: %msg%n")
                            .build())
                    .withFilePattern(file + ".%d{yyyy-MM-dd}.gz")
                    .withPolicy(TimeBasedTriggeringPolicy.newBuilder().withInterval(1).build())
                    .withStrategy(DefaultRolloverStrategy.newBuilder().withMax("7").build())
                    .build();
            appender.start();
            config.addAppender(appender);

            boolean alsoConsole = "true".equalsIgnoreCase(System.getenv("MCAGENT_LOG_CONSOLE"));
            List<String> names = loggerNames();
            for (String name : names) {
                LoggerConfig logger = new LoggerConfig(name, Level.INFO, alsoConsole);
                logger.addAppender(appender, Level.INFO, null);
                config.addLogger(name, logger);
            }
            context.updateLoggers();

            LOG.info("MC Agent logs go to {} (console: {})", file.toAbsolutePath(),
                    alsoConsole ? "also" : "no");
        } catch (Throwable t) {
            // Losing the log file must never take the bot down; the console keeps working either way.
            LOG.warn("Could not route MC Agent logs to their own file; they stay in the console", t);
        }
    }

    /**
     * Every logger name this mod uses, read from the resource the build generates.
     *
     * <p>One config per name because log4j2 splits logger names on '.', not '/': "mcagent/brain" has
     * no relationship to "mcagent" as far as the hierarchy is concerned, so there is nothing to hang
     * a single entry on. The list is generated from the source at build time (see the
     * {@code mcagentLoggerNames} task), so a new logger cannot quietly stay in the console.
     */
    private static List<String> loggerNames() {
        List<String> names = new ArrayList<>();
        names.add(ROOT_LOGGER);
        try (java.io.InputStream in = Logging.class.getResourceAsStream("mcagent-loggers.txt")) {
            if (in == null) {
                return names;
            }
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\\R")) {
                String name = line.trim();
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        } catch (Throwable t) {
            LOG.warn("Could not read the logger-name list; some MC Agent lines may stay in the console", t);
        }
        return names;
    }

    /** Where the mod's log goes: {@code MCAGENT_LOG_FILE}, or {@code logs/mcagent.log}. */
    private static Path logFile() {
        String configured = System.getenv("MCAGENT_LOG_FILE");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("logs", "mcagent.log");
    }
}
