// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;
import javax.servlet.http.*;
import org.apache.http.client.utils.*;
import com.brsanthu.googleanalytics.*;
import com.brsanthu.googleanalytics.request.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.*;
import io.micrometer.core.instrument.*;

/*
 * This is used wherever we need analytics without having knowledge of analytics context.
 * Context is instead embedded in every instance of this class.
 * We can thus emit analytics in widgets without knowing where are those widgets used.
 */
public abstract class SiteAnalytics {
	/*
	 * Brsanthu's library uses recursive generics, which propagates complexity everywhere.
	 * Just using GoogleAnalyticsRequest<?> causes fluent setters to return Object.
	 * On the other hand, parameterizing everything with <T extends GoogleAnalyticsRequest<T>>
	 * results in excessive complexity, unchecked casts, and other dirty code.
	 * 
	 * We will solve this problem by converting all incoming hits to a universal hit type,
	 * which is then used in all general hit processing code
	 * and it is also compatible with brsantu's API.
	 */
	public static class AnyHit extends GoogleAnalyticsRequest<AnyHit> {
		public AnyHit(GoogleAnalyticsRequest<?> original) {
			for (Map.Entry<GoogleAnalyticsParameter, String> entry : original.getParameters().entrySet())
				parameter(entry.getKey(), entry.getValue());
			for (Map.Entry<String, String> entry : original.customDimensions().entrySet())
				customDimensions.put(entry.getKey(), entry.getValue());
			for (Map.Entry<String, String> entry : original.custommMetrics().entrySet())
				customMetrics.put(entry.getKey(), entry.getValue());
		}
		/*
		 * Our universal hit supports cloning, which is a missing feature in brsanthu's library.
		 */
		@Override public AnyHit clone() {
			return new AnyHit(this);
		}
		/*
		 * We will define some extra methods on our universal hit
		 * to make it easy to fill it with general parameters.
		 * 
		 * First our antispam hack. Just add secret cookie as custom dimension
		 * and filter for it in analytics settings.
		 */
		public AnyHit authCookie(String auth) {
			return customDimension(1, auth);
		}
		/*
		 * If we don't have user agent string, leave the default unchanged.
		 */
		public AnyHit optionalUA(String ua) {
			if (ua == null)
				return this;
			return userAgent(ua);
		}
		/*
		 * This method is general, without any reference to SitePage to make it usable outside site context.
		 * We aren't doing any URL rewriting here yet. Rewriting is applied later when hit is being fired.
		 */
		public AnyHit page(PushPage page) {
			Map<String, String> headers = page.request().headers();
			userIp(ip(headers.get("X-Forwarded-For")));
			anonymizeIp(true);
			documentReferrer(headers.get("Referer"));
			userLanguage(language(headers.get("Accept-Language")));
			userAgent(headers.get("User-Agent"));
			documentUrl(page.request().url());
			return this;
		}
		/*
		 * Useful for servlets and websockets.
		 */
		public AnyHit request(HttpServletRequest request) {
			userIp(ip(request.getHeader("X-Forwarded-For")));
			anonymizeIp(true);
			userLanguage(Exceptions.log().get(() -> language(request.getHeader("Accept-Language"))).orElse(null));
			userAgent(request.getHeader("User-Agent"));
			documentUrl(request.getRequestURL().toString());
			documentTitle(request.getRequestURI());
			return this;
		}
		public AnyHit request(ReactiveServletRequest request) {
			userIp(ip(request.headers().get("X-Forwarded-For")));
			anonymizeIp(true);
			userLanguage(Exceptions.log().get(() -> language(request.headers().get("Accept-Language"))).orElse(null));
			userAgent(request.headers().get("User-Agent"));
			documentUrl(request.url());
			documentTitle(Exceptions.sneak().get(() -> new URI(request.url())).getPath());
			return this;
		}
		/*
		 * For privacy reasons, we don't want Google to know actual user and browser IDs.
		 * We will hash them with secret salt to create one-way mapping from our IDs to Google Analytics IDs.
		 * We don't want this transformation to be reversible,
		 * because we want to anonymize users in GA to protect their privacy.
		 * This is also important for security, because
		 * the IDs might have authentication associated with them.
		 */
		public AnyHit hashIds(String salt) {
			clientId(hash(clientId(), salt));
			userId(hash(userId(), salt));
			return this;
		}
		private static String hash(String value, String salt) {
			if (value == null || value.isEmpty())
				return value;
			MessageDigest sha256 = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256"));
			sha256.update(value.getBytes(StandardCharsets.UTF_8));
			byte[] hash = sha256.digest(salt.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().encodeToString(hash).replace("_", "").replace("-", "").replace("=", "");
		}
		/*
		 * All hosted sites (virtual hosts) actually run on various localhost ports.
		 * We need to replace the localhost URLs with externally visible URLs.
		 */
		public AnyHit rewriteExternalUrl(String host) {
			URIBuilder uri = Exceptions.sneak().get(() -> new URIBuilder(documentUrl())
				.setScheme("https")
				.setPort(-1)
				.setHost(host));
			return documentUrl(uri.toString());
		}
		/*
		 * If it's hard to keep track of numerous analytics properties, we can use one fictional aggregate property.
		 * Hits to this property have original domain name inserted as the first path component.
		 */
		public AnyHit addDomainPrefix(String host) {
			URIBuilder uri = Exceptions.sneak().get(() -> new URIBuilder(documentUrl()));
			uri.setPath("/" + host + uri.getPath());
			return documentUrl(uri.toString());
		}
	}
	/*
	 * Only one method must be implemented for every analytics sink.
	 * This method should do all general hit processing and likely forward to one or more lower level analytics sinks.
	 * This enables clean procedural processing of hits while keeping the possibility of stacking implementations.
	 * The supplied hit can be modified, i.e. no need to clone it at every stage.
	 * We provide named constructor that takes a consumer for easy use.
	 */
	public abstract void send(AnyHit hit);
	public static SiteAnalytics create(Consumer<AnyHit> handler) {
		return new SiteAnalytics() {
			@Override public void send(AnyHit hit) {
				handler.accept(hit);
			}
		};
	}
	/*
	 * When server is restarted, all pages reload, which creates artificial analytics hits.
	 * We will start the server with analytics disabled in order to filter out this noise.
	 * We will enable analytics some time (maybe 1 minute) after entering main().
	 * This will also spare us of class loading of GA-related classes while server is busy starting up.
	 * 
	 * We will nevertheless enable analytics when not deployed and when running tests
	 * in order to test as much code as possible during development.
	 */
	private static volatile boolean enabled = SiteRunMode.get() != SiteRunMode.PRODUCTION;
	static {
		SiteLaunch.delay(() -> {
			enabled = true;
		});
	}
	/*
	 * Brsanthu's library has its own counters, but they are specific to every event type.
	 * We want a single global counter for all hit types.
	 * This counts hits that were actually sent, which can be less or more than the number of hits reported.
	 * 
	 * The counter is not dimensional, because Google Analytics website already provides very rich dimensional views.
	 * Adding dimensionality here (by site or hit type) would just add clutter to counters.
	 */
	private static final Counter hitCount = Metrics.counter("analytics.hits");
	/*
	 * All general hit processing must eventually terminate in an actual HTTP request.
	 * Brsanthu's library encourages creating one GoogleAnalytics object per property.
	 * This however leads to instantiation of numerous thread pools and connection pools.
	 * We will instead use single GoogleAnalytics instance for hits to all properties.
	 */
	public static SiteAnalytics live() {
		return live;
	}
	private static final SiteAnalytics live = new SiteAnalytics() {
		final GoogleAnalytics analytics = GoogleAnalytics.builder()
			/*
			 * No hit batching, because brsanthu's library doesn't correctly set queue time in the hit.
			 */
			.withConfig(new GoogleAnalyticsConfig()
				/*
				 * This analytics object won't be used to create new hits,
				 * but we will disable parameter discovery anyway, just in case.
				 * Brsanthu's library is apparently designed for Android apps.
				 * It would fill the request with garbage in server environment.
				 */
				.setDiscoverRequestParameters(false))
			/*
			 * This will instantiate a whole new thread pool and HTTP connection pool.
			 * That's why we aren't setting tracking ID anywhere. We want to share these resources.
			 */
			.build();
		@Override public void send(AnyHit hit) {
			if (enabled) {
				hitCount.increment();
				/*
				 * Log exceptions just in case, but none are expected,
				 * because brsanthu's library annoyingly logs random connection errors to slf4j logger,
				 * relying on external filtering and making it harder to count or otherwise handle errors.
				 */
				Exceptions.log().run(() -> hit
					/*
					 * Looking into brsanthu's code, GoogleAnalytics interface is implemented
					 * by GoogleAnalyticsImpl, which also implements GoogleAnalyticsExecutor.
					 * Sadly, this is not exposed in the API, which is why we have this hacky cast here.
					 */
					.setExecutor((GoogleAnalyticsExecutor)analytics)
					/*
					 * We are using thread pool in brsanthu's library here,
					 * We definitely don't want to block any code on HTTP requests.
					 */
					.sendAsync());
			}
		}
	};
	/*
	 * No-op analytics is used as a fallback, so that application code doesn't have to check for null everywhere.
	 */
	public static SiteAnalytics none() {
		return none;
	}
	private static final SiteAnalytics none = new SiteAnalytics() {
		@Override public void send(AnyHit hit) {
		}
	};
	/*
	 * Unit test analytics runs the hit-producing code, but it doesn't send the hit anywhere.
	 * This ensures high code coverage during tests without abusing analytics servers.
	 * 
	 * Brsanthu started work on validating requests, which would be useful in tests,
	 * but it is nowhere near implemented and the API is not defined yet.
	 * 
	 * Without validation, this is identical to the no-op analytics.
	 */
	public static SiteAnalytics test() {
		return none;
	}
	/*
	 * Application code uses one of the following hit constructors,
	 * fills them with local parameters, and calls send() or sendAsync() on the hit.
	 * Hits constructed here are configured to redirect into send() method of this analytics sink.
	 */
	public PageViewHit pageView() {
		return prepare(new PageViewHit());
	}
	public EventHit event() {
		return prepare(new EventHit());
	}
	public TimingHit timing() {
		return prepare(new TimingHit());
	}
	private <T extends GoogleAnalyticsRequest<T>> T prepare(T hit) {
		hit.setExecutor(new GoogleAnalyticsExecutor() {
			@Override public CompletableFuture<GoogleAnalyticsResponse> postAsync(GoogleAnalyticsRequest<?> hit) {
				send(new AnyHit(hit));
				return null;
			}
			@Override public GoogleAnalyticsResponse post(GoogleAnalyticsRequest<?> hit) {
				send(new AnyHit(hit));
				return null;
			}
		});
		return hit;
	}
	/*
	 * This helper method takes contents of Accept-Language header and
	 * returns user's language in the 'en-US' or 'en' format.
	 */
	private static String language(String accepts) {
		if (accepts == null)
			return null;
		/*
		 * The header can list multiple languages, so we just pick the first one.
		 */
		String[] alternatives = accepts.split(",");
		if (alternatives.length == 0)
			return null;
		String first = alternatives[0];
		/*
		 * If there's priority attached to the language (e.g. 'en-US;q=0.5'), we strip the priority field.
		 */
		String[] parts = first.split(";");
		if (parts.length == 0)
			return null;
		return parts[0].trim().toLowerCase();
	}
	/*
	 * We might be running behind reverse proxy (nginx, Apache, ...) or even behind HAProxy,
	 * which means the socket doesn't carry user's real IP address.
	 * We will discover the IP address from X-Forwarded-For header that reverse proxy sets for us.
	 */
	public static String ip(String xff) {
		if (xff == null)
			return null;
		/*
		 * X-Forwarded-For may contain multiple IP addresses.
		 * In this case, the first recognizable address is the real one.
		 */
		for (String candidate : Arrays.stream(xff.split(",")).map(String::trim).collect(toList())) {
			try {
				/*
				 * We want to skip junk IP addresses, which might have found their way
				 * into X-Forwarded-For via various localhost, company, or ISP proxies.
				 * We will be weeding out IP addresses that cannot be parsed or that belong in some special address range.
				 */
				InetAddress ip = InetAddress.getByName(candidate);
				if (ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isLoopbackAddress() || ip.isMulticastAddress())
					continue;
				if (ip instanceof Inet6Address && ip.getAddress()[0] == (byte)0xfd)
					continue;
				byte[] address = ip.getAddress();
				if (IntStream.rangeClosed(0, address.length).allMatch(i -> address[i] == 0))
					continue;
				/*
				 * Perform local IP address anonymization to protect user's privacy.
				 * We are also instructing GA to anonymize IPs on GA end, but it is technically and legally safer to anonymize locally.
				 * Anonymization rules are the same as those used by GA, i.e. zero last 80 bits of IPv6 and last 8 bits of IPv4.
				 */
				int anonymizedBytes = ip instanceof Inet6Address ? 10 : 1;
				for (int i = 0; i < anonymizedBytes; ++i)
					address[address.length - i - 1] = 0;
				return InetAddress.getByAddress(address).getHostAddress();
			} catch (Throwable t) {
			}
		}
		return null;
	}
}
