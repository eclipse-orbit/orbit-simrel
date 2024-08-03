/**
 * Copyright (c) 2024 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.orbit.derby.generator;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class DerbyGenerator {

	private static final Pattern IGNORED_RESOURCES_PATTERN = Pattern
			.compile(".*/lib/(derbyrun|derbyoptionaltools).jar");

	private static final Pattern LIBRARY_JAR_PATTERN = Pattern.compile("lib/.*\\.jar");

	public static void main(String[] args) throws Exception {
		var arguments = new ArrayList<>(Arrays.asList(args));

		var version = getArgument(arguments, "-version");

		var contentHandler = new ContentHandler(getArgument(arguments, "-cache"));

		var target = Path.of(getArgument(arguments, "-target")).toRealPath();

		// https://dlcdn.apache.org//db/derby/db-derby-10.17.1.0/db-derby-10.17.1.0-lib-debug.zip
		// https://dlcdn.apache.org//db/derby/db-derby-10.17.1.0/db-derby-10.17.1.0-src.zip

		var binary = contentHandler.getCachedContent(
				"https://dlcdn.apache.org/db/derby/db-derby-" + version + "/db-derby-" + version + "-lib-debug.zip");

		var classes = createBinaryArtifact(binary, target.resolve("artifact-bin.jar"));

		var source = contentHandler
				.getCachedContent(
						"https://dlcdn.apache.org/db/derby/db-derby-" + version + "/db-derby-" + version + "-src.zip")
				.toRealPath();

		createSourceArtifact(source, target.resolve("artifact-src.jar"), classes);
	}

	private static Set<String> createBinaryArtifact(Path source, Path target) throws IOException {
		var classes = new HashSet<String>();
		Files.deleteIfExists(target);
		System.out.println("> " + source + " -> " + target);
		try (var sourceFileSystem = FileSystems.newFileSystem(source);
				var targetFileSystem = FileSystems.newFileSystem(target, Map.of("create", "true"));) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (IGNORED_RESOURCES_PATTERN.matcher(file.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}

					var relativePath = file.subpath(1, file.getNameCount());
					if (LIBRARY_JAR_PATTERN.matcher(relativePath.toString()).matches()) {
						System.out.println("----" + file);
						createBinaryArtifact(file, targetFileSystem, classes);
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}
		return classes;
	}

	private static void createBinaryArtifact(Path nestedJar, FileSystem targetFileSystem, Set<String> classes)
			throws IOException {
		String jarName = nestedJar.getFileName().toString().replaceAll("\\.jar$", "");
		try (var sourceFileSystem = FileSystems.newFileSystem(nestedJar)) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					var name = file.toString();

					if (name.endsWith("/module-info.class") || name.endsWith("/MANIFEST.MF")) {
						return FileVisitResult.CONTINUE;
					}

					if (name.endsWith("/NOTICE") || name.endsWith("LICENSE")) {
						name += "-" + jarName;
					} else if (name.endsWith(".class") && !name.contains("$")) {
						classes.add(name.replaceAll("\\.class$", ".java"));
					}

					var targetFile = targetFileSystem.getPath(name);
					Files.createDirectories(targetFile.getParent());

					if (name.startsWith("/META-INF/services/") && Files.isRegularFile(targetFile)) {
						System.out.println(" > " + file + " +> " + targetFile);
						try (var out = Files.newOutputStream(targetFile, StandardOpenOption.APPEND)) {
							Files.copy(file, out);
						}
					} else {
						System.out.println(" > " + file + " -> " + targetFile);
						Files.copy(file, targetFile);
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static void createSourceArtifact(Path source, Path target, Set<String> classes) throws IOException {
		Files.deleteIfExists(target);
		System.out.println("> " + source + " -> " + target);
		try (var sourceFileSystem = FileSystems.newFileSystem(source);
				var targetFileSystem = FileSystems.newFileSystem(target, Map.of("create", "true"));) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					var name = file.toString();
					if (name.endsWith(".java")) {
						int index = name.lastIndexOf("/org/apache");
						if (index != -1) {
							String className = name.substring(index);
							if (classes.remove(className)) {
								var targetFile = targetFileSystem.getPath(className);
								Files.createDirectories(targetFile.getParent());
								System.out.println(" > " + file + " -> " + targetFile);
								Files.copy(file, targetFile);
							}
						}
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}

		if (!classes.isEmpty()) {
			for (String missingSourceClass : classes) {
				System.out.println(" > " + missingSourceClass + " -> <missing>");
			}
		}
	}

	private static String getArgument(List<String> arguments, String name) {
		var index = arguments.indexOf(name);
		if (index >= 0) {
			arguments.remove(index);
			if (index < arguments.size()) {
				return arguments.remove(index);
			}
		}

		return null;
	}

	private static class ContentHandler {
		private final Path cache;

		private final HttpClient httpClient;

		public ContentHandler(String cache) {
			httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
					.cookieHandler(new CookieManager()).build();
			try {
				if (cache != null) {
					this.cache = Path.of(cache);
				} else {
					this.cache = Files.createTempDirectory("org.eclipse.orbit.derby-generator-cache");
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		protected <T> T basicGetContent(URI uri, BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
			var requestBuilder = HttpRequest.newBuilder(uri).GET();
			var request = requestBuilder.build();
			var response = httpClient.send(request, bodyHandler);
			var statusCode = response.statusCode();
			if (statusCode != 200) {
				throw new IOException("status code " + statusCode + " -> " + uri);
			}

			return response.body();
		}

		protected Path getCachePath(URI uri) {
			var decodedURI = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
			var isFolder = decodedURI.endsWith("/");
			var uriSegments = decodedURI.split("[:/?#&;]+");
			var relativePath = String.join("/", uriSegments).replace('=', '-');
			if (isFolder) {
				relativePath += "_._";
			}

			var result = cache.resolve(relativePath);
			return result;
		}

		public Path getCachedContent(String uri) throws IOException {
			return getCachedContent(URI.create(uri));
		}

		public Path getCachedContent(URI uri) throws IOException {
			var path = getCachePath(uri);
			if (!isCacheFresh(path)) {
				try {
					var content = basicGetContent(uri, BodyHandlers.ofInputStream());
					writeContent(path, content);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
			}

			return path;
		}

		private boolean isCacheFresh(Path path) throws IOException {
			if (Files.isRegularFile(path)) {
				var lastModifiedTime = Files.getLastModifiedTime(path);
				var now = System.currentTimeMillis();
				var age = now - lastModifiedTime.toMillis();
				var ageInHours = age / 1000 / 60 / 60;
				if (ageInHours < 8 * 3 * 7) {
					return true;
				}
			}

			return false;
		}

		private void writeContent(Path path, InputStream content) throws IOException {
			createDirectories(path);
			Files.copy(content, path);
		}

		private synchronized void createDirectories(Path path) throws IOException {
			Files.createDirectories(path.getParent());
		}
	}

}
