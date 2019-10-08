// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.google.common.base.*;
import com.rits.cloning.*;

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
		ExecutorMetrics.monitor(fullname(), executor);
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
}
