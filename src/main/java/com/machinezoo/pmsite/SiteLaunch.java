// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.lang.management.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import com.machinezoo.noexception.*;

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
		/*
		 * If delayed initialization has already started, run the delayed task immediately.
		 * Run it on the bulk executor to get lower priority and to avoid blocking the caller.
		 * 
		 * To avoid cyclic initialization dependencies with SiteThread,
		 * we are forcing initialization of SiteThread.bulk() in flush() below.
		 */
		SiteThread.bulk().submit(Exceptions.log().runnable(runnable));
	}
	/*
	 * On single-core virtual servers, it is better to call this method after a delay.
	 * This is because many delayed tasks trigger class loading and code compilation.
	 * JVM's code compiler threads run at high priority,
	 * consuming all of the single CPU core until there is nothing to compile.
	 * That means even delayed tasks running on a low-priority thread can hog the CPU
	 * as long as they involve a lot of class loading and compilation.
	 * The only defense is to not run them for some time after application launch.
	 * This applies to some degree also to dual-core virtual servers.
	 * Quad-core servers are likely better off flushing delayed tasks immediately.
	 * 
	 * For similar reasons, spread should be positive on single-core and dual-core servers
	 * while quad-core servers are probably better off with zero spread.
	 */
	public static void flush(Duration spread) {
		/*
		 * Force initialization of executor used by this class before flushing delayed tasks.
		 * This is done because SiteLaunch.delay() is called during initialization of executors.
		 * We want to keep accepting delayed tasks while the executors are still initializing.
		 */
		SiteThread.timer();
		SiteThread.bulk();
		List<Runnable> flushed;
		synchronized (SiteLaunch.class) {
			flushed = delayed;
			delayed = null;
		}
		/*
		 * Tolerate duplicate calls to flush() that result in the list of delayed tasks being already null.
		 */
		if (flushed != null) {
			profile("Scheduling delayed tasks.");
			for (Runnable runnable : flushed) {
				/*
				 * We will schedule the task on scheduled executor, but since delayed tasks can be heavy,
				 * we will use scheduled executor only as a timer and hop immediately to bulk executor.
				 * Callers of SiteLaunch.delay() are free to submit code that causes hop to yet another executor,
				 * but we enforce use of the bulk executor by default in order to keep API of this class simple.
				 * 
				 * To avoid cyclic initialization dependencies with SiteThread,
				 * we pre-initialize SiteThread.timer() and SiteThread.bulk() above.
				 */
				Runnable scheduled = () -> SiteThread.bulk().submit(Exceptions.log().runnable(runnable));
				/*
				 * If zero or negative spread was provided, use max() to ensure that random number generator doesn't throw.
				 */
				long delay = ThreadLocalRandom.current().nextLong(Math.max(1, spread.toMillis()));
				SiteThread.timer().schedule(scheduled, delay, TimeUnit.MILLISECONDS);
			}
			Runnable message = () -> profile("All delayed tasks have been started, some might be still running.");
			SiteThread.timer().schedule(() -> SiteThread.bulk().submit(message), spread.toMillis(), TimeUnit.MILLISECONDS);
		}
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
