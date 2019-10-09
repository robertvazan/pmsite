// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.*;

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
}
