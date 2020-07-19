// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import com.machinezoo.stagean.*;

/*
 * A lot of web app behavior depends on whether it is running in production or development environment.
 * There is no way in Java to determine run status of the application automatically, so configuration is needed.
 * There is a number of ways to configure the app for production/development.
 * We want a single global setting that controls everything (or at least defaults for everything)
 * instead of having two huge configuration files, one for production and one for development.
 * We can either get this single configuration from environment (resources, properties, ...) or we can set it in main().
 * Requiring main() to call a method is the easy/lazy solution and also a very flexible one,
 * because main() can use arbitrary logic to detect production/development environment.
 * The downside is that any code running too early will use the wrong configuration.
 * We can work around that by setting the configuration as early in main() as possible.
 */
/**
 * Global run mode (production, development, test).
 */
@StubDocs
@DraftApi("separate library, configuration via resources & filesystem, custom modes, more predefined modes, isXXX() methods for mode classes")
public enum SiteRunMode {
	/*
	 * We have one extra configuration for unit tests, which might need their own special defaults.
	 */
	TESTS,
	DEVELOPMENT,
	PRODUCTION;
	/*
	 * Unit tests are configured by default, because they don't run main() and thus cannot be configured explicitly.
	 */
	private static SiteRunMode current = TESTS;
	public static SiteRunMode get() {
		return current;
	}
	public static void set(SiteRunMode status) {
		current = status;
	}
}
