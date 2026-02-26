/**
 * Copyright (c) 2026 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.orbit.updater;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class Updater {
	private static final String PLATFORM_VERSION = "4.40.0";

	private static final String PLATFORM_VERSION_MATCHER = "(4\\.[3-9][0-9]\\.[0-9])";

	private static final String TYCHO_VERSION = "5.0.2";

	private static final String TYCHO_VERSION_MATCHER = "([0-9]+\\.[0-9]+\\.[0-9]+)(?:-SNAPSHOT)?";

	private Path root;

	public static void main(String[] args) throws IOException {
		var currentWorkingDirectory = Path.of(".").toRealPath();
		if (!currentWorkingDirectory.toString().replace("\\", "/").endsWith("/org.eclipse.orbit.updater")) {
			throw new RuntimeException("Expecting to run this from the org.eclipse.orbit.updater project");
		}
		new Updater(currentWorkingDirectory.resolve("..").toRealPath()).update();
		new Updater(currentWorkingDirectory.resolve("../../orbit-legacy").toRealPath()).update();
	}

	private Map<Path, String> contents = new LinkedHashMap<>();

	public Updater(Path root) {
		this.root = root;
	}

	private String getContent(Path path) throws IOException {
		return contents.containsKey(path) ? contents.get(path) : Files.readString(path);
	}

	private void apply(Path path, String pattern, String... replacements) throws IOException {
		var content = getContent(path);
		var matcher = Pattern.compile(pattern).matcher(content);
		if (matcher.find()) {
			var modifiedContent = new StringBuilder(content);
			var offset = 0;
			do {
				var delta = modifiedContent.length();
				for (var group = matcher.groupCount(); group >= 1; --group) {
					modifiedContent.replace(offset + matcher.start(group), offset + matcher.end(group),
							replacements[group - 1]);
				}
				delta -= modifiedContent.length();
				offset -= delta;
			} while (matcher.find());
			if (!modifiedContent.toString().equals(content)) {
				contents.put(path, modifiedContent.toString());
			}
		}
	}

	private void visit(Path file) throws IOException {
		var relativePathName = root.relativize(file).toString().replace('\\', '/');
		var fileName = file.getFileName().toString();
		if (fileName.equals("pom.xml")) {
			apply(file, "<tycho-version>" + TYCHO_VERSION_MATCHER + "</tycho-version>", TYCHO_VERSION);
		}
		if (relativePathName.endsWith("org.eclipse.orbit.legacy-feature/feature.xml")) {
			apply(file, "version=\"" + PLATFORM_VERSION_MATCHER + "\\.qualifier\"", PLATFORM_VERSION);
		} else if (relativePathName.endsWith("org.eclipse.orbit.legacy-feature/pom.xml")) {
			apply(file, "<version>" + PLATFORM_VERSION_MATCHER + "-SNAPSHOT</version>", PLATFORM_VERSION);
		} else if (fileName.endsWith(".launch")) {
			apply(file, "-version " + PLATFORM_VERSION_MATCHER + "[^.0-9]", PLATFORM_VERSION);
		}

	}

	private void update() throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				visit(file);
				return super.visitFile(file, attrs);
			}

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				String fileName = dir.getFileName().toString();
				if ("target".equals(fileName) || "updates".equals(fileName) || "bin".equals(fileName)
						|| "cache".equals(fileName) || fileName.startsWith(".") || "publish".equals(fileName)
						|| "repository".equals(fileName)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				Path relativePath = root.relativize(dir);
				if (relativePath.startsWith("updates")) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return super.preVisitDirectory(dir, attrs);
			}
		});

		saveModifiedContents();
	}

	private void saveModifiedContents() throws IOException {
		for (var entry : contents.entrySet()) {
			Files.writeString(entry.getKey(), entry.getValue());
		}
	}
}
