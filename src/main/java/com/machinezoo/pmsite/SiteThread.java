// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.google.common.base.*;
import com.machinezoo.noexception.*;
import com.rits.cloning.*;

public class SiteThread {
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
			fullname = "StemThread";
		if (suffix != null)
			fullname += "-" + suffix;
		return fullname;
	}
	private Runnable runnable;
	public SiteThread runnable(Runnable runnable) {
		this.runnable = runnable;
		return this;
	}
	private boolean catchAll;
	public SiteThread catchAll(boolean catchAll) {
		this.catchAll = catchAll;
		return this;
	}
	private boolean daemon = true;
	public SiteThread daemon(boolean daemon) {
		this.daemon = daemon;
		return this;
	}
	private boolean numbered = true;
	public SiteThread numbered(boolean numbered) {
		this.numbered = numbered;
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
	private static Map<String, AtomicLong> counters = new HashMap<>();
	public SiteThread clone() {
		return Cloner.standard().shallowClone(this);
	}
	public Thread thread() {
		if (runnable == null)
			throw new IllegalStateException();
		Runnable entry = runnable;
		if (catchAll)
			entry = Exceptions.log().runnable(runnable);
		String fullname = fullname();
		if (numbered) {
			synchronized (SiteThread.class) {
				fullname += "-" + counters.computeIfAbsent(fullname, n -> new AtomicLong()).incrementAndGet();
			}
		}
		Thread thread = new Thread(entry, fullname);
		thread.setDaemon(daemon);
		thread.setPriority(priority);
		return thread;
	}
	private static class ThreadBuilderFactory implements ThreadFactory {
		final SiteThread options;
		ThreadBuilderFactory(SiteThread options) {
			this.options = options;
		}
		@Override public Thread newThread(Runnable runnable) {
			return options.clone()
				.runnable(runnable)
				.thread();
		}
	}
	public ThreadFactory factory() {
		return new ThreadBuilderFactory(clone());
	}
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
