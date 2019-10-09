// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.lang.management.*;
import java.util.*;
import org.slf4j.*;

/*
 * This utility class performs application launch optimizations.
 */
public class SiteLaunch {
	/*
	 * Non-essential tasks can be scheduled to be executed only after the application finishes essential initialization.
	 */
	private static List<Runnable> delayed = new ArrayList<>();
	public static void delay(Runnable runnable) {
		synchronized (SiteLaunch.class) {
			if (delayed != null) {
				delayed.add(runnable);
				return;
			}
		}
		runnable.run();
	}
	public static void flush() {
		List<Runnable> flushed;
		synchronized (SiteLaunch.class) {
			flushed = delayed;
			delayed = null;
		}
		for (Runnable runnable : flushed)
			runnable.run();
	}
	/*
	 * We can get the MX beans eagerly, because profiling messages are some of the first code to run.
	 */
	private static final RuntimeMXBean runtimeMX = ManagementFactory.getRuntimeMXBean();
	private static final ClassLoadingMXBean loadingMX = ManagementFactory.getClassLoadingMXBean();
	/*
	 * Only report every reached point in the application once.
	 */
	private static class SeenKey {
		final Object[] parts;
		SeenKey(String message, Object[] parameters) {
			parts = new Object[parameters.length + 1];
			parts[0] = message;
			for (int i = 0; i < parameters.length; ++i)
				parts[i + 1] = parameters[i];
		}
		@Override public boolean equals(Object obj) {
			return obj instanceof SeenKey && Arrays.equals(parts, ((SeenKey)obj).parts);
		}
		@Override public int hashCode() {
			return Arrays.hashCode(parts);
		}
	}
	private static final Set<SeenKey> seen = new HashSet<>();
	/*
	 * This is a crude application launch profiler.
	 * It will eat up log space, but it requires no instrumentation or tooling.
	 * All it requires is a little extra logging clutter in the code.
	 * If someone finds the logs superfluous, they are easy to filter out by logger name.
	 */
	private static final Logger logger = LoggerFactory.getLogger(SiteLaunch.class);
	public static void profile(String format, Object... arguments) {
		SeenKey key = new SeenKey(format, arguments);
		synchronized (SiteLaunch.class) {
			if (seen.contains(key))
				return;
			seen.add(key);
		}
		String formatEx = format + " [{}ms, {} classes]";
		Object[] argumentsEx = Arrays.copyOf(arguments, arguments.length + 2);
		argumentsEx[arguments.length] = String.format("%,d", runtimeMX.getUptime());
		argumentsEx[arguments.length + 1] = String.format("%,d", loadingMX.getLoadedClassCount());
		logger.info(formatEx, argumentsEx);
	}
}
