/**
 * Copyright (c) 2023 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.orbit.ant.generator;

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
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class AntGenerator {

	public static void main(String[] args) throws Exception {
		var arguments = new ArrayList<>(Arrays.asList(args));

		var version = getArgument(arguments, "-version");

		var contentHandler = new ContentHandler(getArgument(arguments, "-cache"));

		var target = Path.of(getArgument(arguments, "-target")).toRealPath();
		var binary = contentHandler
				.getCachedContent("https://dlcdn.apache.org/ant/binaries/apache-ant-" + version + "-bin.zip");
		var jaiClasses = new LinkedHashSet<String>();
		var jars = new ArrayList<Path>(createBinaryArtifact(binary, target.resolve("artifact-bin.jar"), jaiClasses));
		var source = contentHandler
				.getCachedContent("https://dlcdn.apache.org/ant/source/apache-ant-" + version + "-src.zip")
				.toRealPath();
		createSourceArtifact(source, target.resolve("artifact-src.jar"), jaiClasses);

		var bndBundleClasspathInstruction = target.resolve("Bundle-ClassPath.properties");
		var bndBundleClasspathInstructions = new ArrayList<String>();
		bndBundleClasspathInstructions
				.add("# These contents are generated and if they change should be copied to MavenBDN.target");
		bndBundleClasspathInstructions.add("#");
		for (int i = 0, last = jars.size() - 1; i <= last; ++i) {
			bndBundleClasspathInstructions.add((i == 0 ? "Bundle-ClassPath:       " : "                        ")
					+ jars.get(i) + (i == last ? "" : ",\\"));
		}

		Files.write(bndBundleClasspathInstruction, bndBundleClasspathInstructions);

		System.out.println(String.join("\n", bndBundleClasspathInstructions));
	}

	private static Set<Path> createBinaryArtifact(Path source, Path target, Set<String> jaiClasses) throws IOException {
		var jars = new TreeSet<Path>();
		var ignoreResourcesPattern = Pattern.compile("/[^/]+/manual(/.*)?|.*jai\\.(pom|jar)");
		var libraryPattern = Pattern.compile("lib/.*\\.jar");
		Files.deleteIfExists(target);
		System.out.println("> " + source + " -> " + target);
		try (var sourceFileSystem = FileSystems.newFileSystem(source);
				var targetFileSystem = FileSystems.newFileSystem(target, Map.of("create", "true"));) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (ignoreResourcesPattern.matcher(dir.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (ignoreResourcesPattern.matcher(file.toString()).matches()) {
						if ("ant-jai.jar".equals(file.getFileName().toString())) {
							collectJaiClasses(file, jaiClasses);
						}
						return FileVisitResult.SKIP_SUBTREE;
					}

					var relativePath = file.subpath(1, file.getNameCount());
					if (libraryPattern.matcher(relativePath.toString()).matches()) {
						jars.add(relativePath);
					}

					var targetFile = targetFileSystem.getPath("/").resolve(relativePath);
					Files.createDirectories(targetFile.getParent());
					System.out.println(" > " + file + " -> " + targetFile);

					Files.copy(file, targetFile);
					return FileVisitResult.CONTINUE;
				}
			});
		}

		return jars;
	}

	private static void collectJaiClasses(Path jai, Set<String> jaiClasses) throws IOException {
		try (var sourceFileSystem = FileSystems.newFileSystem(jai)) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					var path = file.toString();
					if (path.endsWith(".class")) {
						// Keep the . for prefix matching later.
						jaiClasses.add(path.substring(0, path.length() - "class".length()));
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static void createSourceArtifact(Path source, Path target, Set<String> jaiClasses) throws IOException {
		var includedResources = Pattern.compile("/[^/]+/src/main(/.*)?");
		Files.deleteIfExists(target);
		System.out.println("> " + source + " -> " + target);
		try (var sourceFileSystem = FileSystems.newFileSystem(source);
				var targetFileSystem = FileSystems.newFileSystem(target, Map.of("create", "true"));) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (dir.getNameCount() > 3 && !includedResources.matcher(dir.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (!includedResources.matcher(file.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					var targetFile = targetFileSystem.getPath("/").resolve(file.subpath(3, file.getNameCount()));
					var targetFileString = targetFile.toString();
					for (var jaiClass : jaiClasses) {
						if (targetFileString.startsWith(jaiClass)) {
							System.out.println(" x " + file + " -> " + targetFile);
							return FileVisitResult.CONTINUE;
						}
					}

					Files.createDirectories(targetFile.getParent());
					System.out.println(" > " + file + " -> " + targetFile);

					Files.copy(file, targetFile);
					return FileVisitResult.CONTINUE;
				}
			});
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
					this.cache = Files.createTempDirectory("org.eclipse.orbit.ant-generator-cache");
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
