package com.example.bootiful_javafx;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@code FXMLLoader} is an interpreter: the document is the program, and every element in
 * it is a class name the loader resolves at runtime. The static analysis that decides what
 * goes into a native image sees a call to {@code load(InputStream)} and nothing beyond it -
 * the whole scene graph is behind a wall of reflection.
 * <p>
 * AOT processing, though, runs on an ordinary JVM with the entire classpath in front of it.
 * That is the opening: read the documents at build time, see for ourselves which types they
 * name, and hand native-image that vocabulary. Add a {@code <?import ?>} to an
 * {@code .fxml} file and the hints follow along on the next build - no second list to keep
 * in sync.
 */
class FxmlRuntimeHints implements RuntimeHintsRegistrar {

	/* every FXML document this application ships, wherever it sits on the classpath */
	private static final String DOCUMENTS = "fxml/**/*.fxml";

	/* `<?import javafx.scene.control.Button?>`, or `<?import javafx.scene.control.*?>` */
	private static final Pattern IMPORT = Pattern.compile("<\\?import\\s+([\\w.$*]+)\\s*\\?>");

	private static final String WILDCARD = ".*";

	/*
	 * The loader's own machinery mostly calls itself directly - `BeanAdapter`, the
	 * `@NamedArg` builders standing in for the types with no no-arg constructor
	 * (`Insets`, `Font`, `Image`) - so the analysis follows it unaided. The one thing it
	 * cannot follow is the loader's own bootstrap: `FXMLLoaderHelper` reaches back for
	 * `javafx.fxml.FXMLLoader` by name, through `Class.forName`, to force it to
	 * initialize. Without a hint the very first `new FXMLLoader()` dies on a
	 * `ClassNotFoundException` for a class that is demonstrably in the image.
	 */
	private final List<String> loader = List.of("javafx.fxml");

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		var loaderToUse = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
		Hints.classesInPackages(loaderToUse, this.loader)
			.forEach(type -> hints.reflection().registerType(type, Hints.EVERYTHING));

		/*
		 * The documents are read at runtime, so they have to ship as resources. Whatever
		 * they point at with `@` - the stylesheet on the root element here - has to be in
		 * the image too, or the loader fails the document outright.
		 */
		hints.resources().registerPattern(DOCUMENTS);

		var resolver = new PathMatchingResourcePatternResolver(loaderToUse);
		var imported = imports(
				Hints.resources(resolver, ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + DOCUMENTS));

		/*
		 * A single import is a type; a wildcard is a package to enumerate. Either way the
		 * loader will ask for these by name, and `registerTypeIfPresent` keeps a stale
		 * import in a document from failing the build.
		 */
		imported.stream()
			.filter(name -> !name.endsWith(WILDCARD))
			.forEach(name -> hints.reflection().registerTypeIfPresent(loaderToUse, name, Hints.EVERYTHING));
		var packages = imported.stream()
			.filter(name -> name.endsWith(WILDCARD))
			.map(name -> name.substring(0, name.length() - WILDCARD.length()))
			.toList();
		Hints.classesInPackages(loaderToUse, packages)
			.forEach(type -> hints.reflection().registerType(type, Hints.EVERYTHING));
	}

	private static Collection<String> imports(Collection<Resource> documents) {
		var imports = new TreeSet<String>();
		for (var document : documents) {
			try (var in = document.getInputStream()) {
				var matcher = IMPORT.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				while (matcher.find()) {
					imports.add(matcher.group(1));
				}
			} //
			catch (IOException ioException) {
				throw new UncheckedIOException("could not read [" + document + "]", ioException);
			}
		}
		return imports;
	}

}
