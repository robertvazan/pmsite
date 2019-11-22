// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import org.apache.commons.io.*;
import org.apache.http.*;
import org.apache.http.client.utils.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;

/*
 * Temporary hash-based caching until we switch to data URIs + CDN uploads.
 */
public class SiteResources {
	private final SiteMappings mappings;
	public SiteResources(SiteMappings mappings) {
		this.mappings = mappings;
	}
	private static class HashedContent {
		final String mime;
		final String hash;
		final byte[] content;
		HashedContent(String mime, String hash, byte[] content) {
			this.mime = mime;
			this.hash = hash;
			this.content = content;
		}
	}
	private final Map<String, ReactiveAsyncCache<HashedContent>> map = new HashMap<>();
	/*
	 * We include class reference in our resource coordinates, because that's how resources are usually located.
	 * We could have required absolute resource path, but that would complicate the code as well as package moves/renames.
	 */
	public SiteResources content(String path, Class<?> clazz, String filename) {
		String mime = MimeTypes.byPath(filename).orElse("application/octet-stream");
		Supplier<HashedContent> supplier = Exceptions.sneak().supplier(() -> {
			SiteReload.watch();
			try (InputStream stream = clazz.getResourceAsStream(filename)) {
				if (stream == null)
					throw new IllegalStateException("Resource not found: " + clazz.getName() + ": " + filename);
				byte[] content = IOUtils.toByteArray(stream);
				byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(content);
				String hash = Base64.getUrlEncoder().encodeToString(sha256).replace("_", "").replace("-", "").replace("=", "");
				return new HashedContent(mime, hash, content);
			}
		});
		ReactiveAsyncCache<HashedContent> cache = new ReactiveAsyncCache<>(supplier)
			.draft(new HashedContent(mime, null, new byte[0]));
		map.put(path, cache);
		mappings.map(path, new ResourceServlet(cache));
		return this;
	}
	public SiteResources content(String path, Class<?> clazz) {
		return content(path, clazz, path.substring(path.lastIndexOf('/') + 1));
	}
	/*
	 * Since most code just uses resources in one package, we can identify all resources relative to single class.
	 * This is not only more concise, it is likely to be faster, avoiding class loading during server startup.
	 */
	private Class<?> root;
	public SiteResources root(Class<?> root) {
		this.root = root;
		return this;
	}
	public SiteResources content(String path) {
		if (root == null)
			throw new IllegalStateException();
		return content(path, root);
	}
	public synchronized String hash(String path) {
		ReactiveAsyncCache<HashedContent> hashed = map.get(path);
		if (hashed == null)
			return null;
		return hashed.get().hash;
	}
	@SuppressWarnings("serial") private static class ResourceServlet extends ReactiveServlet {
		final ReactiveAsyncCache<HashedContent> cache;
		ResourceServlet(ReactiveAsyncCache<HashedContent> cache) {
			this.cache = cache;
		}
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			HashedContent hashed = cache.get();
			/*
			 * Resources need long-lived caching and at the same time immediate refresh upon change.
			 * We force the refresh by embedding URLs with cache busters in all pages that use the resource.
			 * It may however happen that we receive request for a hash that we don't have.
			 * This can easily happen when page is served before server upgrade and resource is served afterwards.
			 * We solve this problem by checking the hash every time and disable caching if we serve the wrong version.
			 * Page streaming will soon afterwards push correct URL to the client
			 * and we then get an opportunity to serve the right content with caching enabled.
			 * 
			 * This is unfortunately broken in the reverse direction when the correct new hash is served,
			 * but old server responds with old version of the content. The problem will correct itself
			 * after page reload, but there's no way to fix it while the page is still displayed.
			 * The only solution is to use data URIs and upload the more frequently served ones
			 * to S3 to be served via CDN, subsequently replacing data URIs with CDN URLs.
			 */
			String requestedHash = Exceptions.sneak().get(() -> new URIBuilder(request.url())).getQueryParams().stream()
				.filter(p -> p.getName().equals("v"))
				.findFirst()
				.map(NameValuePair::getValue)
				.orElse(null);
			ReactiveServletResponse response = new ReactiveServletResponse();
			if (hashed.hash == null || !hashed.hash.equals(requestedHash))
				response.headers().put("Cache-Control", "no-cache, no-store");
			else
				response.headers().put("Cache-Control", "public, max-age=31536000"); // one year
			response.headers().put("Content-Type", hashed.mime);
			response.data(ByteBuffer.wrap(hashed.content));
			return response;
		}
	}
}
