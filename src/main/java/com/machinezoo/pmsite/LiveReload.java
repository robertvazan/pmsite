// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.hookless.*;
import com.machinezoo.noexception.*;

public class LiveReload {
	private static final Path[] roots = new Path[] {
		Paths.get("target", "classes")
	};
	private static final Duration[] delays = new Duration[] {
		Duration.ZERO,
		Duration.ofMillis(100),
		Duration.ofMillis(300),
		Duration.ofSeconds(1),
		Duration.ofSeconds(3)
	};
	private final Duration delay;
	private static final Instant launch = Instant.now();
	private static final ReactiveVariable<Instant> timestamp = new ReactiveVariable<Instant>(launch);
	private static final String buster = "?v=" + launch.toEpochMilli();
	public static Instant watch() {
		if (SiteRunMode.get() == SiteRunMode.PRODUCTION)
			return launch;
		return timestamp.get();
	}
	public static String buster() {
		if (SiteRunMode.get() == SiteRunMode.PRODUCTION)
			return buster;
		return "?v=" + watch().toEpochMilli();
	}
	static {
		if (SiteRunMode.get() != SiteRunMode.PRODUCTION)
			for (Duration delay : delays)
				new LiveReload(delay).start();
	}
	private LiveReload(Duration delay) {
		this.delay = delay;
	}
	private void start() {
		new SiteThread()
			.owner(LiveReload.class)
			.runnable(Exceptions.log().runnable(this::run))
			.lowestPriority()
			.thread()
			.start();
	}
	private void run() {
		Instant last = modificationTime();
		while (true) {
			Exceptions.sneak().run(() -> TimeUnit.MILLISECONDS.sleep(delay.toMillis()));
			Instant current = modificationTime();
			if (current.compareTo(last) != 0) {
				last = current;
				Exceptions.sneak().run(() -> TimeUnit.MILLISECONDS.sleep(delay.toMillis()));
				timestamp.set(Instant.now());
			}
		}
	}
	private static Instant modificationTime() {
		long millis = Arrays.stream(roots)
			.flatMap(Exceptions.sneak().function(p -> Files.walk(p)))
			.filter(Files::isRegularFile)
			.mapToLong(p -> p.toFile().lastModified())
			.max().orElse(0);
		return Instant.ofEpochMilli(millis);
	}
}
