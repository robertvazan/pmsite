// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
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
				return new String(bytes, StandardCharsets.UTF_8);
			}
		}));
	}
	/*
	 * Bindings are basically constants that are expanded when referenced in the template.
	 */
	private final Map<String, Supplier<? extends DomContent>> bindings = new HashMap<>();
	public SiteTemplate bind(String name, Supplier<? extends DomContent> content) {
		bindings.put(name, content);
		return this;
	}
	public SiteTemplate bindText(String name, Supplier<String> content) {
		bindings.put(name, () -> new DomText(content.get()));
		return this;
	}
	/*
	 * Rewrites on the other hand visit every element and try to transform it.
	 * Rewriters are slow and error-prone. Should probably replace them with an expanded version of bindings.
	 */
	private final List<Function<DomElement, ? extends DomContent>> rewriters = new ArrayList<>();
	public SiteTemplate rewrite(Function<DomElement, ? extends DomContent> rewriter) {
		rewriters.add(rewriter);
		return this;
	}
	/*
	 * The above-defined bindings and rewriters are applied by the template compiler below.
	 * This is done recursively, so bindings and rewriters can nest and expand into more bindings and rewritable elements.
	 * It also converts "fragment" elements into DomFragment instances (relevant only for top-level element).
	 */
	private DomContent compile(DomContent source) {
		if (!(source instanceof DomContainer))
			return source;
		if (!(source instanceof DomElement))
			return new DomFragment().add(((DomContainer)source).children().stream().map(this::compile));
		DomElement element = (DomElement)source;
		if (element.tagname().equals("binding")) {
			String of = element.attribute("of").value();
			Supplier<? extends DomContent> binding = bindings.get(of);
			if (binding == null)
				throw new NullPointerException("No such binding: " + of);
			DomContent expanded = handleErrors(binding);
			List<DomAttribute> attributes = element.attributes().stream().filter(a -> !a.name().equals("of")).collect(toList());
			if (!attributes.isEmpty() && expanded != null) {
				if (!(expanded instanceof DomElement))
					throw new IllegalArgumentException("Attributes used on non-element binding: " + of);
				DomElement rewritten = (DomElement)expanded;
				DomElement extended = new DomElement(rewritten.tagname())
					.key(rewritten.key())
					.id(rewritten.id())
					.set(rewritten.attributes())
					.set(attributes)
					.add(rewritten.children());
				for (DomListener listener : rewritten.listeners())
					extended.subscribe(listener);
				expanded = extended;
			}
			return expanded;
		}
		for (Function<DomElement, ? extends DomContent> rewriter : rewriters) {
			DomContent compiled = handleErrors(() -> rewriter.apply(element));
			if (compiled != null)
				return compile(compiled);
		}
		DomContainer container;
		if (element.tagname().equals("fragment"))
			container = new DomFragment();
		else {
			DomElement compiled = new DomElement(element.tagname())
				.key(element.key())
				.id(element.id())
				.set(element.attributes());
			for (DomListener listener : element.listeners())
				compiled.subscribe(listener);
			container = compiled;
		}
		container.add(element.children().stream().map(this::compile));
		return container;
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
					case "main":
					case "article":
					case "fragment":
						content = compile(child);
						break;
					case "title":
						title = child.text();
						break;
					case "description":
						description = child.text();
						break;
					default:
						throw new IllegalStateException("Unrecognized template element: " + child.tagname());
					}
				}
				break;
			case "body":
			case "main":
			case "article":
			case "fragment":
				content = compile(parsed);
				break;
			default:
				throw new IllegalStateException("Unrecognized top element: " + parsed.tagname());
			}
		} catch (Throwable ex) {
			if (SiteRunMode.get() != SiteRunMode.DEVELOPMENT)
				throw ex;
			content = error(ex);
		}
		return this;
	}
	/*
	 * Content may be a fragment or an element. The API below supports both cases.
	 */
	private DomContent content;
	public DomContent content() {
		return content;
	}
	public DomElement element() {
		return (DomElement)handleErrors(() -> (DomElement)content);
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
}
