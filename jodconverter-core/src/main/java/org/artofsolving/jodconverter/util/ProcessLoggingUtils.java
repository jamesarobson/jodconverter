package org.artofsolving.jodconverter.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * Utilites for logging the output on the standard streams of a running process
 * 
 * this basically has to create two threads which will consume the InputStreams of the process and log them to a logger, whenever a new line was written
 */
public class ProcessLoggingUtils {

	public static void logProcessOutput(final Process process, final long pid, final Logger logger) {
		logProcessOutput(process, pid, logger, Level.FINER);
	}

	public static void logProcessOutput(final Process process, final long pid, final Logger logger, final Level level) {
		logProcessOutput(process, pid, logger, level, level);
	}

	public static void logProcessOutput(final Process process, final long pid, final Logger logger, final Level stdOutLevel, final Level stdErrLevel) {
		logProcessOutput(process, Charset.defaultCharset(), pid, logger, stdOutLevel, stdErrLevel);
	}

	public static void logProcessOutput(final Process process, Charset cs, final long pid, final Logger logger, final Level stdOutLevel, final Level stdErrLevel) {
		ThreadGroup threadGroup = new ThreadGroup(String.format("loggingthreads for process [ %s ]", pid)) {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				logger.log(Level.WARNING, String.format("exception on loggingthread for process [ %s ]", pid), e);
				super.uncaughtException(t, e);
			}
		};
		threadGroup.setDaemon(true);

		Thread stdOutLoggerThread = createLoggingThread(threadGroup, pid, process.getInputStream(), cs, "stdout", logger, stdOutLevel);
		Thread stdErrLoggerThread = createLoggingThread(threadGroup, pid, process.getErrorStream(), cs, "stderr", logger, stdErrLevel);

		start(stdOutLoggerThread);
		start(stdErrLoggerThread);
	}

	private static void start(Thread t) {
		if (t != null) {
			t.start();
		}
	}

	private static Thread createLoggingThread(final ThreadGroup threadGroup, final long pid, final InputStream stream, Charset cs, final String name,
	        final Logger logger, final Level level) {
		if (stream == null) {
			return null;
		}
		Runnable r = createLoggingRunnable(pid, name, stream, cs, logger, level);
		Thread t = new Thread(threadGroup, r, String.format("loggingthread for %s of process [ %s ]", name, pid));
		t.setDaemon(true);
		return t;
	}

	private static Runnable createLoggingRunnable(final long pid, final String name, final InputStream stream, Charset cs, final Logger logger,
	        final Level level) {
		if (stream == null) {
			return null;
		}
		String prefix = String.format("%s[%s]: ", name, pid);
		return new LoggingRunnable(stream, cs, logger, level, prefix);
	}

	private static final class LoggingRunnable implements Runnable {
		private final Level level;
		private final BufferedReader reader;
		private final String prefix;
		private final Logger logger;

		private LoggingRunnable(InputStream stream, Charset cs, Logger logger, Level level, String prefix) {
			this.reader = new BufferedReader(new InputStreamReader(stream, cs));
			this.logger = logger;
			this.level = level;
			this.prefix = prefix;
		}

		@Override
		public void run() {
			String line;
			try {
				while ((line = reader.readLine()) != null) {
					if (logger.isLoggable(level)) {
						this.logger.log(level, prefix + line);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(prefix + "error reading line", e);
			}
		}
	}
}
