// Part of PMSite: https://pmsite.machinezoo.com
module com.machinezoo.pmsite {
	exports com.machinezoo.pmsite;
	exports com.machinezoo.pmsite.utils;
	/*
	 * Only for metrics (number of loaded classes) that are logged during app launch.
	 */
	requires java.management;
	/*
	 * Otherwise we get ClassNotFoundException: java.sql.Timestamp
	 * when SiteConfiguration initializes SitemapGenerator.
	 */
	requires java.sql;
	requires com.machinezoo.stagean;
	/*
	 * Transitive, because we return CloseableScope from many methods.
	 * Transitivity should be removed once CloseableScope is in separate library.
	 */
	requires transitive com.machinezoo.noexception;
	/*
	 * Transitive, because we reference ReactivePreferences in the API.
	 */
	requires transitive com.machinezoo.hookless;
	/*
	 * Transitive, because we are exposing ReactiveServlet implementations.
	 */
	requires transitive com.machinezoo.hookless.servlets;
	/*
	 * Transitive, because DomX classes are referenced in the API extensively.
	 */
	requires transitive com.machinezoo.pushmode;
	/*
	 * SLF4J is pulled in transitively via noexception and then via hookless,
	 * but the transitive dependency will be removed in future versions of noexception.
	 */
	requires org.slf4j;
	requires micrometer.core;
	requires org.apache.commons.lang3;
	requires org.apache.commons.io;
	requires one.util.streamex;
	requires com.google.common;
	requires com.google.gson;
	requires it.unimi.dsi.fastutil;
	requires org.apache.httpcomponents.httpclient;
	requires org.apache.httpcomponents.httpcore;
	requires org.eclipse.jetty.servlet;
	/*
	 * Transitive, because jsitemapgenerator is used in the API. This is not ideal of course. Should look for some better solution.
	 */
	requires transitive cz.jiripinkas.jsitemapgenerator;
	/*
	 * 4.x will be a module, but Apache Commons Math has glacial release schedule.
	 */
	requires commons.math3;
	/*
	 * Not yet a module: https://github.com/mikehardy/google-analytics-java/issues/240
	 * Maybe we should switch to another library. Perhaps there is an official Java API from Google?
	 */
	requires google.analytics.java;
}
