// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite.utils;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import com.google.common.reflect.*;
import com.google.gson.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/**
 * MIME type database.
 */
@StubDocs
@DraftApi("find or create dedicated MIME library")
public class SiteMime {
	private static final Map<String, String> byExtension = Exceptions.sneak().get(() -> {
		try (InputStream stream = SiteMime.class.getResourceAsStream("mime.json")) {
			@SuppressWarnings("serial") Type type = new TypeToken<Map<String, String>>() {
			}.getType();
			return new Gson().fromJson(new InputStreamReader(stream), type);
		}
	});
	public static Optional<String> byPath(String path) {
		int dot = path.lastIndexOf('.');
		String extension = dot < 0 ? "" : path.substring(dot + 1);
		return Optional.ofNullable(byExtension.get(extension));
	}
}
