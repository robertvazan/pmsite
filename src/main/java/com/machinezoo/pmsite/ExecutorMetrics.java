// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.*;
import java.util.concurrent.*;
import io.micrometer.core.instrument.*;

public class ExecutorMetrics {
	private static List<ExecutorMetrics> delayed = new ArrayList<>();
	private final String name;
	private final Executor executor;
	private boolean pending;
	private long start;
	private double latency;
	private ExecutorMetrics(String name, Executor executor) {
		this.name = name;
		this.executor = executor;
	}
	private void start() {
		List<Tag> tags = Arrays.asList(Tag.of("executor", name));
		Metrics.gauge("executor.latency", tags, executor, e -> latency());
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
	public static synchronized <T extends Executor> T monitor(String name, T executor) {
		ExecutorMetrics metrics = new ExecutorMetrics(name, executor);
		if (delayed != null)
			delayed.add(metrics);
		else
			metrics.start();
		return executor;
	}
	public static synchronized void startAll() {
		for (ExecutorMetrics metrics : delayed)
			metrics.start();
		delayed = null;
	}
	private synchronized double latency() {
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
