package com.machinezoo.pmsite;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.machinezoo.noexception.*;
import com.rits.cloning.*;

public class ThreadBuilder {
	private Class<?> owner;
	public ThreadBuilder owner(Class<?> owner) {
		this.owner = owner;
		return this;
	}
	private String name;
	public ThreadBuilder name(String name) {
		this.name = name;
		return this;
	}
	private String suffix;
	public ThreadBuilder suffix(String suffix) {
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
	public ThreadBuilder runnable(Runnable runnable) {
		this.runnable = runnable;
		return this;
	}
	private boolean catchAll;
	public ThreadBuilder catchAll(boolean catchAll) {
		this.catchAll = catchAll;
		return this;
	}
	private boolean daemon = true;
	public ThreadBuilder daemon(boolean daemon) {
		this.daemon = daemon;
		return this;
	}
	private boolean numbered = true;
	public ThreadBuilder numbered(boolean numbered) {
		this.numbered = numbered;
		return this;
	}
	private boolean background;
	public ThreadBuilder background(boolean background) {
		this.background = background;
		return this;
	}
	private static Map<String, AtomicLong> counters = new HashMap<>();
	public ThreadBuilder clone() {
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
			synchronized (ThreadBuilder.class) {
				fullname += "-" + counters.computeIfAbsent(fullname, n -> new AtomicLong()).incrementAndGet();
			}
		}
		Thread thread = new Thread(entry, fullname);
		thread.setDaemon(daemon);
		thread.setPriority(background ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
		return thread;
	}
	private static class ThreadBuilderFactory implements ThreadFactory {
		final ThreadBuilder options;
		ThreadBuilderFactory(ThreadBuilder options) {
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
	public ThreadBuilder parallelism(int parallelism) {
		this.parallelism = parallelism;
		return this;
	}
	public ThreadBuilder unboundedParallelism() {
		return parallelism(-1);
	}
	public ThreadBuilder hardwareParallelism() {
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
	private static final ExecutorService compute = new ThreadBuilder()
		.name("compute")
		.hardwareParallelism()
		.executor();
	public static ExecutorService compute() {
		return compute;
	}
	private static final ExecutorService bulk = new ThreadBuilder()
		.name("bulk")
		.hardwareParallelism()
		.background(true)
		.executor();
	public static ExecutorService bulk() {
		return bulk;
	}
}
