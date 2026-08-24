package com.example.bootiful_javafx;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The two things every hint registrar in this application needs: "register everything
 * about this type" and "find me every type in these packages." Both registrars run during
 * AOT processing, on a normal JVM, with the whole classpath in front of them - so a
 * package can be enumerated then and turned into hints, even though it could never be
 * enumerated inside the finished native image.
 */
abstract class Hints {

	/*
	 * `MemberCategory` has accumulated deprecated members - the fine-grained
	 * public-vs-declared distinctions - so take the ones that are left. For JavaFX the
	 * answer really is "everything": the toolkit reflects over public API, private native
	 * peers, and package-private skins alike.
	 */
	static final MemberCategory[] EVERYTHING = Stream.of(MemberCategory.values()) //
		.filter(category -> {//
			try {
				return !MemberCategory.class.getField(category.name()) //
					.isAnnotationPresent(Deprecated.class);
			} //
			catch (NoSuchFieldException noSuchField) {
				throw new IllegalStateException(noSuchField);
			}
		}) //
		.toArray(MemberCategory[]::new);

	static List<String> classNames(Class<?>... classes) {
		return Stream.of(classes).map(Class::getName).toList();
	}

	@SafeVarargs
	static List<String> flatten(Collection<String>... groups) {
		return Stream.of(groups).flatMap(Collection::stream).toList();
	}

	/**
	 * Enumerate the types in each package by reading the class files off the classpath -
	 * no class loading, no initialization, just the names in the constant pool.
	 */
	static Collection<TypeReference> classesInPackages(ClassLoader classLoader, Collection<String> packageNames) {
		var resolver = new PathMatchingResourcePatternResolver(classLoader);
		var metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
		var classNames = new TreeSet<String>();
		for (var packageName : packageNames) {
			var pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
					+ ClassUtils.convertClassNameToResourcePath(packageName) + "/*.class";
			for (var resource : resources(resolver, pattern)) {
				if (isSynthetic(resource.getFilename())) {
					continue;
				}
				try {
					classNames.add(metadataReaderFactory.getMetadataReader(resource).getClassMetadata().getClassName());
				} //
				catch (IOException ioException) {
					throw new UncheckedIOException("could not read [" + resource + "]", ioException);
				}
			}
		}
		return classNames//
			.stream() //
			.map(TypeReference::of) //
			.collect(Collectors.toUnmodifiableSet());
	}

	static Collection<Resource> resources(ResourcePatternResolver resolver, String pattern) {
		try {
			return Stream.of(resolver.getResources(pattern)).filter(Resource::isReadable).toList();
		} //
		catch (IOException ioException) {
			throw new UncheckedIOException("could not resolve [" + pattern + "]", ioException);
		}
	}

	private static boolean isSynthetic(String filename) {
		return filename == null || filename.startsWith("package-info") || filename.startsWith("module-info");
	}

}
