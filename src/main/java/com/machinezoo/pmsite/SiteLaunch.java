// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.lang.management.*;
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
		schedule(runnable);
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
	 */
	public static void flush() {
		List<Runnable> flushed;
		synchronized (SiteLaunch.class) {
			flushed = delayed;
			delayed = null;
		}
		for (Runnable runnable : flushed)
			schedule(runnable);
	}
	/*
	 * Delayed tasks run on a background thread in order to avoid blocking interactive code,
	 * which is often the trigger that caused delayed task to be submitted.
	 * Even flush() above schedules tasks on the executor in order to run the tasks with low priority
	 * and with concurrency of one, so that the CPU is burdened as little as possible.
	 */
	private static ExecutorService executor;
	private static synchronized void schedule(Runnable runnable) {
		/*
		 * Construct delayed task executor lazily in order to avoid loading of many related classes.
		 * We don't want to use Suppliers.memoize() here for the same reason.
		 */
		if (executor == null) {
			executor = new SiteThread()
				.owner(SiteLaunch.class)
				/*
				 * Delayed tasks are all non-interactive. Give them low priority.
				 */
				.lowestPriority()
				/*
				 * We don't want this little helper executor to appear in metrics.
				 * This also prevents possible cyclic initialization dependencies between SiteLaunch and SiteThread.
				 */
				.monitored(false)
				.executor();
		}
		executor.submit(Exceptions.log().runnable(runnable));
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
