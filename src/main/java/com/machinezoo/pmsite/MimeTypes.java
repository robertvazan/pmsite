// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import com.google.common.reflect.*;
import com.google.gson.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

@DraftCode("find or create dedicated MIME library")
class MimeTypes {
	private static final Map<String, String> byExtension = Exceptions.sneak().get(() -> {
		try (InputStream stream = MimeTypes.class.getResourceAsStream("mime.json")) {
			@SuppressWarnings("serial") Type type = new TypeToken<Map<String, String>>() {
			}.getType();
			return new Gson().fromJson(new InputStreamReader(stream), type);
		}
	});
	static Optional<String> byPath(String path) {
		int dot = path.lastIndexOf('.');
		String extension = dot < 0 ? "" : path.substring(dot + 1);
		return Optional.ofNullable(byExtension.get(extension));
	}
}
