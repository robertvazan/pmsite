// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import javax.xml.parsers.*;
import org.apache.commons.io.*;
import org.xml.sax.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;

/*
 * There are surely better template engines, but we are lazy to integrate and customize them.
 * A simple stupid XML file will do the job well enough.
 */
public class SiteTemplate {
	/*
	 * Theoretically, the template could come from anywhere, including database.
	 */
	private final Supplier<String> supplier;
	public SiteTemplate(Supplier<String> supplier) {
		this.supplier = supplier;
	}
	/*
	 * But on simple sites, all templates will come from application resources.
	 */
	public static SiteTemplate resource(Class<?> clazz, String path) {
		return new SiteTemplate(Exceptions.sneak().supplier(() -> {
			SiteReload.watch();
			try (InputStream resource = clazz.getResourceAsStream(path)) {
				if (resource == null)
					throw new IllegalStateException("Cannot find resource " + path + " of class " + clazz.getName());
				byte[] bytes = IOUtils.toByteArray(resource);
				/*
				 * Get rid of the unicode byte order mark at the beginning of the file
				 * in order to avoid "content is not allowed in prolog" exceptions from Java's XML parser.
				 */
				return new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
			}
		}));
	}
	/*
	 * Allow loading of metadata alone, without expanding bindings in actual content.
	 * This is useful for building various indexes of pages.
	 */
	private boolean metadataOnly;
	public SiteTemplate metadataOnly(boolean metadataOnly) {
		this.metadataOnly = metadataOnly;
		return this;
	}
	/*
	 * Bindings are used to add dynamic content into templates.
	 * This is not as good as proper template engine, but it will work reasonably well.
	 */
	private final Map<String, SiteBinding> bindings = new HashMap<>();
	public SiteTemplate bind(SiteBinding binding) {
		bindings.put(binding.name(), binding);
		return this;
	}
	/*
	 * Allow specifying hosting page. Bindings will then be able to use page metadata.
	 */
	private SitePage page;
	public SiteTemplate page(SitePage page) {
		this.page = page;
		return this;
	}
	/*
	 * The above-defined bindings are expanded by the template compiler below.
	 * This is done recursively, so bindings can nest and expand into more custom elements.
	 */
	private DomContent compile(DomContent source) {
		/*
		 * Leaves (DomText nodes) are not expanded at all.
		 */
		if (!(source instanceof DomContainer))
			return source;
		/*
		 * The only possible non-element container is DomFragment, but let's keep this general.
		 * DomFragment cannot be expanded, so we just compile its children.
		 */
		if (!(source instanceof DomElement))
			return new DomFragment().add(((DomContainer)source).children().stream().map(this::compile));
		DomElement element = (DomElement)source;
		/*
		 * Custom element names must be prefixed with "x-", so that we can detect misconfigured bindings.
		 * XML namespaces might be better, but DomElement doesn't support them.
		 */
		if (element.tagname().startsWith("x-")) {
			SiteBinding binding = bindings.get(element.tagname().substring(2));
			if (binding == null)
				throw new IllegalStateException("No such binding: " + element.tagname());
			SiteBindingContext context = new SiteBindingContext() {
				@Override public DomElement source() {
					return element;
				}
				@Override public SiteTemplate template() {
					return SiteTemplate.this;
				}
				@Override public SitePage page() {
					return page;
				}
			};
			DomContent expanded = binding.expand(context);
			if (expanded instanceof DomElement) {
				/*
				 * We want to allow extra attributes on custom elements, especially class for styling.
				 * Here we add attributes declared on source element to the generated element.
				 */
				DomElement generated = (DomElement)expanded;
				List<DomAttribute> attributes = element.attributes().stream()
					.filter(a -> !context.consumed().contains(a.name()))
					.collect(toList());
				if (!attributes.isEmpty()) {
					/*
					 * We cannot edit the generated element directly, because it might be shared or frozen.
					 * We will perform a fast shallow copy, because we only need to change the top-level element.
					 */
					DomElement annotated = new DomElement(generated.tagname())
						.key(generated.key())
						.id(generated.id())
						.set(generated.attributes())
						/*
						 * This is the only change we are making.
						 * Attributes explicitly set on the source element override generated attributes.
						 */
						.set(attributes)
						.add(generated.children());
					for (DomListener listener : generated.listeners())
						annotated.subscribe(listener);
					expanded = annotated;
				}
			}
			/*
			 * Recursively compile the rendered content.
			 * Here we risk infinite recursion if custom elements expand into each other,
			 * but it's rare enough and inconsequential enough to not care.
			 */
			return compile(expanded);
		}
		/*
		 * The element stays as is, but its contents must be compiled.
		 */
		DomElement compiled = new DomElement(element.tagname())
			.key(element.key())
			.id(element.id())
			.set(element.attributes());
		for (DomListener listener : element.listeners())
			compiled.subscribe(listener);
		compiled.add(element.children().stream().map(this::compile));
		return compiled;
	}
	/*
	 * Template must be explicitly loaded, which populates all the visible properties.
	 */
	public SiteTemplate load() {
		String text = supplier.get();
		DomElement parsed = Exceptions.sneak().get(() -> {
			DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
			return DomElement.fromXml(builder.parse(new InputSource(new StringReader(text))).getDocumentElement());
		});
		if (!"template".equals(parsed.tagname()))
			throw new IllegalStateException("Unrecognized top element: " + parsed.tagname());
		for (DomElement child : parsed.elements().collect(toList())) {
			switch (child.tagname()) {
			case "body":
				if (!metadataOnly)
					body = (DomElement)compile(child);
				break;
			case "main":
				if (!metadataOnly)
					main = (DomElement)compile(child);
				break;
			case "article":
				if (!metadataOnly)
					article = (DomElement)compile(child);
				break;
			case "path":
				path = normalizeWhitespace(child.text());
				break;
			case "alias":
				String alias = normalizeWhitespace(child.text());
				if (alias != null)
					aliases.add(alias);
				break;
			case "breadcrumb":
				breadcrumb = normalizeWhitespace(child.text());
				break;
			case "title":
				title = normalizeWhitespace(child.text());
				break;
			case "supertitle":
				supertitle = normalizeWhitespace(child.text());
				break;
			case "description":
				description = normalizeWhitespace(child.text());
				break;
			case "published":
				published = parseDateTime(child.text());
				break;
			case "lead":
				lead = new DomFragment().add(child.children());
				break;
			default:
				throw new IllegalStateException("Unrecognized template element: " + child.tagname());
			}
		}
		return this;
	}
	private static final DateTimeFormatter formatOfDateTime = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd[ HH:mm[:ss]]")
		.parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
		.parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
		.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
		.toFormatter(Locale.ROOT)
		.withZone(ZoneOffset.UTC);
	private static Instant parseDateTime(String formatted) {
		ZonedDateTime parsed = ZonedDateTime.parse(formatted, formatOfDateTime);
		return parsed.toInstant();
	}
	private static final Pattern whitespaceRe = Pattern.compile("\\s+");
	private static String normalizeWhitespace(String text) {
		return whitespaceRe.matcher(text).replaceAll(" ").trim();
	}
	/*
	 * Content may be one of several types of elements. We provide specialized getters for each to ease use of the template.
	 */
	private DomElement body;
	public DomElement body() {
		return body;
	}
	private DomElement main;
	public DomElement main() {
		return main;
	}
	private DomElement article;
	public DomElement article() {
		return article;
	}
	/*
	 * Several metadata fields can be defined in the template. They are exposed here.
	 */
	private String path;
	public String path() {
		return path;
	}
	private List<String> aliases = new ArrayList<>();
	public List<String> aliases() {
		return aliases;
	}
	private String breadcrumb;
	public String breadcrumb() {
		return breadcrumb;
	}
	private String title;
	public String title() {
		return title;
	}
	private String supertitle;
	public String supertitle() {
		return supertitle;
	}
	private String description;
	public String description() {
		return description;
	}
	private Instant published;
	public Instant published() {
		return published;
	}
	private DomFragment lead;
	public DomFragment lead() {
		return lead;
	}
}
