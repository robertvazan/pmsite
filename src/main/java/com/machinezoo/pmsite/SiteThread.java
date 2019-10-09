// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.google.common.base.*;
import com.rits.cloning.*;
import io.micrometer.core.instrument.*;

public class SiteThread {
	/*
	 * There are several ways to name the new thread(s). All of them are optional as we default to "site" name.
	 * There is usually at most one executor per class, so owner's class name should be sufficient.
	 * We however also allow specifying arbitrary thread name.
	 * In case there are two thread pools per class, suffix can be specified to differentiate them.
	 */
	private Class<?> owner;
	public SiteThread owner(Class<?> owner) {
		this.owner = owner;
		return this;
	}
	private String name;
	public SiteThread name(String name) {
		this.name = name;
		return this;
	}
	private String suffix;
	public SiteThread suffix(String suffix) {
		this.suffix = suffix;
		return this;
	}
	private String fullname() {
		String fullname = name;
		if (fullname == null && owner != null)
			fullname = owner.getSimpleName();
		if (fullname == null)
			fullname = "site";
		if (suffix != null)
			fullname += "-" + suffix;
		return fullname;
	}
	/*
	 * Since executors can have multiple threads, we offer to automatically number them.
	 * Numbering is enabled by default, but it is ignored when single thread pool is constructed.
	 */
	private boolean numbered = true;
	public SiteThread numbered(boolean numbered) {
		this.numbered = numbered;
		return this;
	}
	/*
	 * Every group of threads (as identified by full name) has threads numbered from 1.
	 * Thread dumps listing threads xy-1 through xy-4 are much easier to understand than random thread numbers like 13, 36, 57, 119.
	 * Method is synchronized to protect the map.
	 */
	private static Map<String, AtomicLong> numbers = new HashMap<>();
	private static synchronized long number(String fullname) {
		return numbers.computeIfAbsent(fullname, n -> new AtomicLong()).incrementAndGet();
	}
	/*
	 * All threads are daemon threads by default, so that they don't block application shutdown.
	 * We are usually just killing the application instead of shutting it down,
	 * but such shutdown-by-kill is not required and daemon threads can then help.
	 */
	private boolean daemon = true;
	public SiteThread daemon(boolean daemon) {
		this.daemon = daemon;
		return this;
	}
	/*
	 * We allow setting arbitrary priority, but applications usually have only two types of tasks: normal and low priority.
	 * Normal priority is default in order to avoid surprises, but many thread pools should be set to lowest priority,
	 * because they run non-essential and often heavy tasks that compete for CPU with interactive user interface.
	 */
	private int priority = Thread.NORM_PRIORITY;
	public SiteThread priority(int priority) {
		this.priority = priority;
		return this;
	}
	public SiteThread lowestPriority() {
		this.priority = Thread.MIN_PRIORITY;
		return this;
	}
	/*
	 * We allow constructing individual threads, thread pools, and executors.
	 * Runnable is only specified for individual threads.
	 */
	private Runnable runnable;
	public SiteThread runnable(Runnable runnable) {
		this.runnable = runnable;
		return this;
	}
	public Thread thread() {
		if (runnable == null)
			throw new IllegalStateException();
		String fullname = fullname();
		if (numbered)
			fullname += "-" + number(fullname);
		Thread thread = new Thread(runnable, fullname);
		thread.setDaemon(daemon);
		thread.setPriority(priority);
		return thread;
	}
	/*
	 * SiteThread is a mutable builder, but thread factory needs to remember its current state.
	 * We using cloning to accomplish that.
	 */
	public SiteThread clone() {
		return Cloner.standard().shallowClone(this);
	}
	private static class ThreadBuilderFactory implements ThreadFactory {
		final SiteThread options;
		ThreadBuilderFactory(SiteThread options) {
			this.options = options;
		}
		@Override public Thread newThread(Runnable runnable) {
			/*
			 * Keep the original unchanged just in case this is called from two threads.
			 * This solution also avoids long-term reference to the provided runnable.
			 */
			return options.clone()
				.runnable(runnable)
				.thread();
		}
	}
	public ThreadFactory factory() {
		/*
		 * Clone the builder to prevent further changes.
		 */
		return new ThreadBuilderFactory(clone());
	}
	/*
	 * Callers just specify the kind of desired parallelism and we magically pick the right executor for them.
	 */
	private int parallelism = 1;
	public SiteThread parallelism(int parallelism) {
		this.parallelism = parallelism;
		return this;
	}
	public SiteThread unboundedParallelism() {
		return parallelism(-1);
	}
	public SiteThread hardwareParallelism() {
		return parallelism(Runtime.getRuntime().availableProcessors());
	}
	public ExecutorService executor() {
		/*
		 * TODO: We could be smarter here. Instead of using single-thread or fixed-thread executors,
		 * we should try to configure cached thread pool for maximum of 1 or N threads.
		 * That would allow these executors to discard unused threads after a timeout.
		 */
		ExecutorService executor;
		if (parallelism == 1) {
			executor = Executors.newSingleThreadExecutor(clone()
				/*
				 * Disable number if there's going to be just one thread.
				 * This allows us to have numbering enabled by default above.
				 */
				.numbered(false)
				.factory());
		} else if (parallelism > 1)
			executor = Executors.newFixedThreadPool(parallelism, factory());
		else
			executor = Executors.newCachedThreadPool(factory());
		monitor(fullname(), executor);
		return executor;
	}
	/*
	 * We will provide default thread pool for heavy CPU-bound tasks.
	 * It should be used for all heavy reactive computations that could slow down interactive code.
	 * 
	 * We aren't providing similar default for light tasks,
	 * because default hookless thread pool should be used for that.
	 * 
	 * Construct the thread pool lazily, so that this class can be used without it.
	 */
	private static final Supplier<ExecutorService> bulk = Suppliers.memoize(() -> new SiteThread()
		.name("bulk")
		/*
		 * Allow heavy tasks to consume all CPU cores if needed, but see the note below about thread priority.
		 */
		.hardwareParallelism()
		/*
		 * Run all heavy tasks at lower priority. Keep CPU available for fast interactive code.
		 */
		.lowestPriority()
		.executor());
	public static ExecutorService bulk() {
		return bulk.get();
	}
	/*
	 * We will provide comprehensive metrics for the types of executors we support.
	 */
	private static void monitorNow(String name, Executor executor) {
		/*
		 * Metrics are dimensional, keyed by executor name, which could result in explosion of metric count,
		 * but executors used in applications should be reasonably few and shared executors should be preferred.
		 * We can nevertheless add a configuration parameter to disable metrics in the future.
		 */
		List<Tag> tags = Arrays.asList(Tag.of("executor", name));
		LatencyMonitor latency = new LatencyMonitor(executor);
		Metrics.gauge("executor.latency", tags, executor, e -> latency.measure());
		if (executor instanceof ThreadPoolExecutor) {
			ThreadPoolExecutor threaded = (ThreadPoolExecutor)executor;
			Metrics.gauge("executor.threads", tags, threaded, ThreadPoolExecutor::getPoolSize);
			Metrics.gauge("executor.activeThreads", tags, threaded, ThreadPoolExecutor::getActiveCount);
			Metrics.gauge("executor.completedTasks", tags, threaded, ThreadPoolExecutor::getCompletedTaskCount);
			Metrics.gauge("executor.totalTasks", tags, threaded, ThreadPoolExecutor::getTaskCount);
			Metrics.gauge("executor.pendingTasks", tags, threaded, t -> t.getTaskCount() - t.getCompletedTaskCount());
		}
		if (executor instanceof ForkJoinPool) {
			ForkJoinPool forked = (ForkJoinPool)executor;
			Metrics.gauge("executor.activeThreads", tags, forked, ForkJoinPool::getActiveThreadCount);
			Metrics.gauge("executor.runningThreads", tags, forked, ForkJoinPool::getRunningThreadCount);
			Metrics.gauge("executor.threads", tags, forked, ForkJoinPool::getPoolSize);
			Metrics.gauge("executor.submissions", tags, forked, ForkJoinPool::getQueuedSubmissionCount);
			Metrics.gauge("executor.queued", tags, forked, ForkJoinPool::getQueuedTaskCount);
			Metrics.gauge("executor.stolen", tags, forked, ForkJoinPool::getStealCount);
		}
	}
	/*
	 * We need some way to measure executor latency, i.e. how long does it take for a task to be scheduled.
	 * Keeping all latencies low is important in interactive applications.
	 * 
	 * There are several ways to measure latency. The one below is not so good, but it's simple and it mostly works.
	 * It is hooked to micrometer's gauge (see above), which polls it regularly.
	 * Every poll submits a tiny task into the executor and this task then reports how long it took to run.
	 * Since the micrometer gauge needs information immediately, we report results of the previous measurement.
	 * 
	 * This has several disadvantages. Firstly, with 1-minute polling, all measurements are 1+ minute old.
	 * Secondly, this keeps cached thread pool awake, preventing it from stopping all threads.
	 * 
	 * TODO: To improve the situation, we should probably switch to drive-by latency measurement,
	 * intercepting submitted tasks and attaching measurement code to them.
	 * That way measurements alone cannot keep any threads alive and the measurements are always up to date.
	 * We would then likely use micrometer timing API instead of a gauge,
	 * which would actually give us better details about the latency than this crude method.
	 */
	private static class LatencyMonitor {
		final Executor executor;
		LatencyMonitor(Executor executor) {
			this.executor = executor;
		}
		boolean pending;
		long start;
		double latency;
		synchronized double measure() {
			if (!pending) {
				pending = true;
				start = System.nanoTime();
				executor.execute(() -> {
					synchronized (this) {
						pending = false;
						latency = (System.nanoTime() - start) * 0.000_000_001;
					}
				});
				return latency;
			} else
				return (System.nanoTime() - start) * 0.000_000_001;
		}
	}
	/*
	 * Metrics are automatically enabled for all executors produced by this class,
	 * but they can be also enabled explicitly for executors created elsewhere.
	 * 
	 * We allow any Executor here rather than requiring ExecutorService
	 * in order to widen the number of different scenarios where this can be used.
	 */
	private static Set<String> monitoredAlready = new HashSet<>();
	public static void monitor(String name, Executor executor) {
		Objects.requireNonNull(name);
		Objects.requireNonNull(executor);
		synchronized (SiteThread.class) {
			/*
			 * The API makes it easy to mistakenly submit the executor for monitoring twice.
			 * We track names of monitored executors to prevent duplicate monitoring.
			 * People could submit one executor under two names, but that's rare and harmless.
			 */
			if (monitoredAlready.contains(name))
				return;
			monitoredAlready.add(name);
			/*
			 * Don't start monitoring yet if monitoring is delayed.
			 */
			if (delayed != null) {
				delayed.add(new DelayedMonitoring(name, executor));
				return;
			}
		}
		/*
		 * Make sure to run this code outside any synchronized block as it could be very slow
		 * during application launch when classes are still loading and code is being compiled.
		 */
		monitorNow(name, executor);
	}
	/*
	 * Executors are often initialized early during application launch.
	 * We have to be careful not to force loading of too many non-essential classes
	 * as this would have serious implications for performance of server restarts and in-place upgrades.
	 * Since micrometer is fairly heavy in terms of class loading, we allow applications to delay executor monitoring.
	 */
	private static class DelayedMonitoring {
		final String name;
		final Executor executor;
		DelayedMonitoring(String name, Executor executor) {
			this.name = name;
			this.executor = executor;
		}
	}
	private static List<DelayedMonitoring> delayed;
	public static synchronized void delayMetrics() {
		if (delayed == null)
			delayed = new ArrayList<>();
	}
	public static void enableMetrics() {
		List<DelayedMonitoring> enabled;
		synchronized (SiteThread.class) {
			enabled = delayed;
			delayed = null;
		}
		if (enabled != null)
			for (DelayedMonitoring monitoring : enabled)
				monitorNow(monitoring.name, monitoring.executor);
	}
}
