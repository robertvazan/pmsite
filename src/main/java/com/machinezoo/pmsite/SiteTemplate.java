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

public class SiteTemplate {
	private final Supplier<String> supplier;
	private final Map<String, Supplier<? extends DomContent>> bindings = new HashMap<>();
	private final List<Function<DomElement, ? extends DomContent>> rewriters = new ArrayList<>();
	public SiteTemplate(Supplier<String> supplier) {
		this.supplier = supplier;
	}
	public static SiteTemplate resource(Class<?> clazz, String path) {
		return new SiteTemplate(Exceptions.sneak().supplier(() -> {
			SiteReload.watch();
			try (InputStream resource = clazz.getResourceAsStream(path)) {
				if (resource == null)
					throw new IllegalStateException("Cannot find resource " + path + " of class " + clazz.getName());
				return deserialize(IOUtils.toByteArray(resource));
			}
		}));
	}
	private static String deserialize(byte[] file) {
		return new String(file, StandardCharsets.UTF_8).replace("\uFEFF", "");
	}
	public SiteTemplate bind(String name, Supplier<? extends DomContent> content) {
		bindings.put(name, content);
		return this;
	}
	public SiteTemplate bindText(String name, Supplier<String> content) {
		bindings.put(name, () -> new DomText(content.get()));
		return this;
	}
	public SiteTemplate rewrite(Function<DomElement, ? extends DomContent> rewriter) {
		rewriters.add(rewriter);
		return this;
	}
	public DomElement element() {
		return (DomElement)catchAll(() -> (DomElement)compile(load()));
	}
	public DomContent content() {
		return catchAll(() -> compile(load()));
	}
	private DomElement load() {
		String text = supplier.get();
		return Exceptions.sneak().get(() -> {
			DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
			return DomElement.fromXml(builder.parse(new InputSource(new StringReader(text))).getDocumentElement());
		});
	}
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
			DomContent expanded = catchAll(binding);
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
			DomContent compiled = catchAll(() -> rewriter.apply(element));
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
	private static DomContent catchAll(Supplier<? extends DomContent> supplier) {
		try {
			return supplier.get();
		} catch (Throwable ex) {
			if (SiteRunMode.get() == SiteRunMode.PRODUCTION)
				throw ex;
			Exceptions.log().handle(ex);
			return error(ex);
		}
	}
	private static DomElement error(Throwable exception) {
		if (SiteRunMode.get() != SiteRunMode.DEVELOPMENT) {
			Exceptions.sneak().run(() -> {
				throw exception;
			});
		}
		StringWriter writer = new StringWriter();
		ExceptionUtils.printRootCauseStackTrace(exception, new PrintWriter(writer));
		return Html.pre().add(writer.toString());
	}
}
