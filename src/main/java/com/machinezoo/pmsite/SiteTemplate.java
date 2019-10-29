// Part of PMSite: https://pushmode.machinezoo.com
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
import org.apache.commons.lang3.exception.*;
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
	 * Allow loading of metadata alone, without expanding custom elements in actual content.
	 * This is useful to build various indexes of pages.
	 */
	private boolean metadataOnly;
	public SiteTemplate metadataOnly(boolean metadataOnly) {
		this.metadataOnly = metadataOnly;
		return this;
	}
	/*
	 * Custom elements are used to add dynamic content into templates.
	 * It's not as good as proper template engine, but it will do well for us.
	 */
	private final Map<String, Supplier<SiteElement>> bindings = new HashMap<>();
	public SiteTemplate element(String name, Supplier<SiteElement> supplier) {
		bindings.put(name, supplier);
		return this;
	}
	public SiteTemplate element(Supplier<SiteElement> supplier) {
		return element(supplier.get().name(), supplier);
	}
	public SiteTemplate content(String name, Supplier<? extends DomContent> supplier) {
		return element(() -> SiteElement.create(name, supplier));
	}
	public SiteTemplate text(String name, Supplier<String> supplier) {
		return content(name, () -> new DomText(supplier.get()));
	}
	/*
	 * The above-defined custom elements are expanded by the template compiler below.
	 * This is done recursively, so custom elements can nest and expand into more custom elements elements.
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
		 * Elements with name "fragment" are expanded into DomFragment instances (relevant only for top-level element).
		 */
		if (element.tagname().equals("fragment"))
			return new DomFragment().add(element.children().stream().map(this::compile));
		/*
		 * Custom element names must be prefixed with "x-", so that we can detect misconfigured bindings.
		 * XML namespaces might be better, but DomElement doesn't support them.
		 */
		if (element.tagname().startsWith("x-")) {
			Supplier<SiteElement> binding = bindings.get(element.tagname().substring(2));
			if (binding == null)
				throw new IllegalStateException("No such custom element: " + element.tagname());
			/*
			 * Recursively compile the rendered content.
			 * Here we risk infinite recursion if custom elements expand into each other,
			 * but it's rare enough and inconsequential enough to not care.
			 */
			return handleErrors(() -> {
				return compile(binding.get()
					.template(this)
					.source(element)
					.render());
			});
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
	 * We are automatically handling errors from bindings and rewriters and also on the top level.
	 * In development mode, we are producing stack traces to make such errors obvious.
	 * This is probably wrong. Every widget/rewriter should probably have its own error handling.
	 * Or we could have an error handling element, but that would be too verbose.
	 * Or we could just propagate everything, but that would make development harder.
	 */
	private static DomContent handleErrors(Supplier<? extends DomContent> supplier) {
		try {
			return supplier.get();
		} catch (Throwable ex) {
			if (SiteRunMode.get() != SiteRunMode.DEVELOPMENT)
				throw ex;
			Exceptions.log().handle(ex);
			return error(ex);
		}
	}
	private static DomElement error(Throwable ex) {
		StringWriter writer = new StringWriter();
		ExceptionUtils.printRootCauseStackTrace(ex, new PrintWriter(writer));
		return Html.pre().add(writer.toString());
	}
	/*
	 * Template must be explicitly loaded, which populates all the visible properties.
	 */
	public SiteTemplate load() {
		String text = supplier.get();
		try {
			DomElement parsed = Exceptions.sneak().get(() -> {
				DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				return DomElement.fromXml(builder.parse(new InputSource(new StringReader(text))).getDocumentElement());
			});
			switch (parsed.tagname()) {
			case "template":
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
					case "fragment":
						if (!metadataOnly)
							fragment = (DomFragment)compile(child);
						break;
					case "title":
						title = normalizeWhitespace(child.text());
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
				break;
			case "body":
				if (!metadataOnly)
					body = (DomElement)compile(parsed);
				break;
			case "main":
				if (!metadataOnly)
					main = (DomElement)compile(parsed);
				break;
			case "article":
				if (!metadataOnly)
					article = (DomElement)compile(parsed);
				break;
			case "fragment":
				if (!metadataOnly)
					fragment = (DomFragment)compile(parsed);
				break;
			default:
				throw new IllegalStateException("Unrecognized top element: " + parsed.tagname());
			}
		} catch (Throwable ex) {
			if (metadataOnly || SiteRunMode.get() != SiteRunMode.DEVELOPMENT)
				throw ex;
			main = Html.main().add(error(ex));
			body = article = null;
			fragment = null;
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
	 * Content may be a fragment or one of several types of elements.
	 * We provide specialized getters for each to ease use of the template.
	 */
	private DomFragment fragment;
	public DomFragment fragment() {
		return fragment;
	}
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
	private String title;
	public String title() {
		return title;
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
