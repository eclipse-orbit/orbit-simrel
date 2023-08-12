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
package org.eclipse.orbit.maven.signer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Signer {

	private static final Name ECLIPSE_SOURCE_BUNDLE = new Attributes.Name("Eclipse-SourceBundle");

	private static final Name BUNDLE_SYMBOLIC_NAME = new Attributes.Name("Bundle-SymbolicName");

	private static final Name BUNDLE_VERSION = new Attributes.Name("Bundle-Version");

	private static final Pattern QUALIFIER_PATTERN = Pattern
			.compile("bundle-pattern: *(\\S+)\\s+Bundle-Version:\\s+\\$\\{version\\}\\.(\\S+)");

	private static final Pattern ARTIFACT_PATH_PATTERN = Pattern.compile("(plugins|features)[/\\\\](.*)\\.jar");

	private static final Pattern VERSION_PREFIX_PATTERN = Pattern.compile("(version=\"[^\"]+)");

	private Map<Pattern, String> qualifiers = new LinkedHashMap<>();

	private Path sourceRepository;

	private Path targetRepository;

	private boolean sign;

	public static void main(String[] args) throws IOException {
		new Signer(new ArrayList<>(Arrays.asList(args))).sign();
	}

	public Signer(List<String> list) throws IOException {
		sourceRepository = getPathArgument(list, "-source").toAbsolutePath();
		if (!Files.isDirectory(sourceRepository)) {
			throw new IllegalArgumentException("Source repository does not exist: " + sourceRepository);
		}

		targetRepository = getPathArgument(list, "-target").toAbsolutePath();
		if (Files.exists(targetRepository)) {
			throw new IllegalArgumentException("Target repository already exits: " + targetRepository);
		}

		var targetFileContent = Files.readString(getPathArgument(list, "-versions").toAbsolutePath());
		for (var matcher = QUALIFIER_PATTERN.matcher(targetFileContent); matcher.find();) {
			var bundlePattern = matcher.group(1);
			var versionQualifier = matcher.group(2);
			qualifiers.put(Pattern.compile(bundlePattern), versionQualifier);
		}

		sign = "true".equals(getArgument(list, "-sign"));
	}

	public void sign() throws IOException {
		Files.walkFileTree(sourceRepository, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				var relativePath = sourceRepository.relativize(file);
				var matcher = ARTIFACT_PATH_PATTERN.matcher(relativePath.toString());
				if (matcher.matches()) {
					unpackArtifact(file, targetRepository.resolve(matcher.group(1) + "/" + matcher.group(2)));
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private String getQualifier(String bundleSymbolicName) {
		for (var entry : qualifiers.entrySet()) {
			if (entry.getKey().matcher(bundleSymbolicName).matches()) {
				return entry.getValue();
			}
		}

		throw new IllegalArgumentException("No qualifier is specified for :" + bundleSymbolicName);
	}

	public void unpackArtifact(Path jar, Path targetRoot) throws IOException {
		try (var fileSystem = FileSystems.newFileSystem(jar)) {
			var jarRoot = fileSystem.getPath("/");
			Files.walkFileTree(jarRoot, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					var relativePathInJar = jarRoot.relativize(file).toString();
					var targetFile = targetRoot.resolve(relativePathInJar);
					Files.createDirectories(targetFile.getParent());

					if ("META-INF/MANIFEST.MF".equals(relativePathInJar)) {
						try (var input = Files.newInputStream(file); var output = Files.newOutputStream(targetFile)) {
							var manifest = new Manifest(input);
							var mainAttributes = manifest.getMainAttributes();
							var bundleVersion = mainAttributes.get(BUNDLE_VERSION);
							var bundleSymbolicName = mainAttributes.get(BUNDLE_SYMBOLIC_NAME);
							var eclipseSourceBundle = mainAttributes.get(ECLIPSE_SOURCE_BUNDLE);

							var qualifier = getQualifier(bundleSymbolicName.toString());
							String qualifiedBundleVersion = bundleVersion + "." + qualifier;
							mainAttributes.put(BUNDLE_VERSION, qualifiedBundleVersion);

							System.out.println(targetRoot.getFileName() + "/" + relativePathInJar);
							System.out.println(BUNDLE_SYMBOLIC_NAME + ": " + bundleSymbolicName);
							System.out.println(BUNDLE_VERSION + ": " + qualifiedBundleVersion);

							if (eclipseSourceBundle != null) {
								String qualifiedEclipseSourceBundle = VERSION_PREFIX_PATTERN
										.matcher(eclipseSourceBundle.toString()).replaceAll("$1." + qualifier);
								mainAttributes.put(ECLIPSE_SOURCE_BUNDLE, qualifiedEclipseSourceBundle);
								System.out.println(ECLIPSE_SOURCE_BUNDLE + ": " + qualifiedEclipseSourceBundle);
							}

							manifest.write(output);
						}
					} else if ("feature.xml".equals(relativePathInJar)) {
						var featureXMLContent = Files.readString(file);
						for (var entry : qualifiers.entrySet()) {
							featureXMLContent = Pattern
									.compile("(<plugin.*id=\"" + entry.getKey() + "(?:\\.source)?\".*version=\"[^\"]+)")
									.matcher(featureXMLContent).replaceAll("$1." + entry.getValue());
						}

						System.out.println(targetRoot.getFileName() + "/" + relativePathInJar);
						System.out.print(featureXMLContent);
						Files.writeString(targetFile, featureXMLContent);
					} else if (relativePathInJar.endsWith("jnilib")) {
						System.out.println(targetRoot.getFileName() + "/" + relativePathInJar);
						sign(file, targetFile, "https://cbi.eclipse.org/macos/codesign/sign");
//					} else if (relativePathInJar.endsWith("dll")) {
//						System.out.println(targetRoot.getFileName() + "/" + relativePathInJar);
//						sign(file, targetFile, "https://cbi.eclipse.org/authenticode/sign");
					} else {
						Files.copy(file, targetFile);
					}

					return super.visitFile(file, attrs);
				}
			});
		}
	}

	private void sign(Path sourceJNILib, Path targetJNILib, String uri) throws IOException {
		var toSignTarget = Path.of(targetJNILib + "-tosign");
		Files.copy(sourceJNILib, toSignTarget);
		var currentDirectory = targetJNILib.getParent();
		var targetFileName = targetJNILib.getFileName().toString();
		var toSignFileName = toSignTarget.getFileName().toString();
		var processLauncher = !sign ? //
				new ProcessLauncher(true, "cp", toSignFileName, targetFileName) : //
				new ProcessLauncher(true, "curl", "-o", targetFileName, "-F", "file=@" + toSignFileName, uri);
		processLauncher.getBuilder().directory(currentDirectory.toFile());
		processLauncher.execute();
		processLauncher.fullDump(System.out);

		if (!Files.isRegularFile(targetJNILib)) {
			throw new IOException("Failed to create signed library: " + targetJNILib);
		}

		Files.delete(toSignTarget);
	}

	private Path getPathArgument(List<String> arguments, String name) {
		var argument = getArgument(arguments, name);
		if (argument != null) {
			return Path.of(argument);
		}
		return null;
	}

	private String getArgument(List<String> arguments, String name) {
		var index = arguments.indexOf(name);
		if (index >= 0) {
			arguments.remove(index);
			if (index < arguments.size()) {
				return arguments.remove(index);
			}
		}

		return null;
	}

	private static class ProcessLauncher {
		private final boolean verbose;

		private final ProcessBuilder builder;

		private Process process;

		private List<String> err;

		private List<String> out;

		public ProcessLauncher(boolean verbose, String... args) {
			this.verbose = verbose;
			builder = new ProcessBuilder(args);

			// Search the path to find the absolute path of the first argument.
			var environment = builder.environment();
			var path = getEnv("PATH", environment);
			var executableArg = args[0];
			var executable = Paths.get(executableArg);
			Path absoluteExecutablePath = null;
			if (!executable.isAbsolute()) {
				absoluteExecutablePath = search(executableArg, path);
				var fullPath = path;
				if (absoluteExecutablePath == null && File.separatorChar == '\\') {
					// We'll assume that on Windows, Git is installed and that we can use it.
					var extraPath = System.getProperty("org.eclipse.justj.extra.path",
							"C:\\Program Files\\Git\\usr\\bin");
					fullPath = extraPath + File.pathSeparator + fullPath;
					path = fullPath;
					absoluteExecutablePath = search(executableArg, extraPath);

					// This is needed on Windows for base just in case the executable wasn't really
					// on the path.
					// And then we put it in for both case variants because a debug launch worked
					// but a run launch did not. Go figure.
					//
					environment.put("PATH", path);
					environment.put("Path", path);
				} else if (absoluteExecutablePath != null && File.separatorChar == '\\') {
					// Ensure that the path on which it is found is first in the search list so that
					// other things like "find" look here first.
					//
					fullPath = absoluteExecutablePath.getParent() + File.pathSeparator + fullPath;

					// Put it in for both case variants because a debug launch worked but a run
					// launch did not. Go figure.
					//
					environment.put("PATH", fullPath);
					environment.put("Path", fullPath);
				}

				if (absoluteExecutablePath == null) {
					throw new IllegalArgumentException("Executable '" + executableArg
							+ "' not found after searching the following PATH: " + fullPath);
				}
			}

			// Change the build to use the absolute path.
			builder.command().set(0, absoluteExecutablePath.toString());
		}

		public ProcessBuilder getBuilder() {
			return builder;
		}

		public void execute() throws IOException {
			if (verbose) {
				System.out.println(this);
			}

			process = builder.start();
			var stdin = process.getOutputStream();
			stdin.close();

			var stderr = process.getErrorStream();
			var stderrException = new AtomicReference<IOException>();
			var stderrThread = new Thread("stderr-reader") {
				@Override
				public void run() {
					try {
						err = lines(stderr);
					} catch (IOException exception) {
						stderrException.set(exception);
					} finally {
						if (err == null) {
							err = Collections.emptyList();
						}
					}
				}
			};
			stderrThread.start();

			var stdout = process.getInputStream();
			var stdoutException = new AtomicReference<IOException>();
			var stdoutThread = new Thread("stdout-reader") {
				@Override
				public void run() {
					try {
						out = lines(stdout);
					} catch (IOException exception) {
						stdoutException.set(exception);
					} finally {
						if (out == null) {
							out = Collections.emptyList();
						}
					}
				}
			};
			stdoutThread.start();

			try {
				process.waitFor(2, TimeUnit.MINUTES);
				stderrThread.join(1000 * 60);
				stdoutThread.join(1000 * 60);
			} catch (InterruptedException exception) {
				System.err.println("Interrupted");
				fullDump(System.err);
				Thread.currentThread().interrupt();
				return;
			}

			if (stderrException.get() != null) {
				throw stderrException.get();
			}

			if (stdoutException.get() != null) {
				throw stdoutException.get();
			}
		}

		public int exitValue() {
			return process.exitValue();
		}

		public void dump() {
			if (err != null) {
				err.forEach(System.err::println);
			}
			if (out != null) {
				out.forEach(System.out::println);
			}
		}

		public void fullDump(PrintStream out) {
			out.println(this);
			dump();
			out.println("exitValue=" + exitValue());
		}

		private String getEnv(String key, Map<String, String> environment) {
			if (File.separatorChar == '\\') {
				var upperCaseKey = key.toUpperCase();
				for (var entry : environment.entrySet()) {
					if (entry.getKey().toUpperCase().equals(upperCaseKey)) {
						return entry.getValue();
					}
				}
			} else {
				return environment.get(key);
			}

			return null;
		}

		private Path search(String executable, String path) {
			var paths = path.split(File.pathSeparator);
			for (var pathElement : paths) {
				var absoluteExecutable = Paths.get(pathElement, executable);
				if (Files.isExecutable(absoluteExecutable) && Files.isRegularFile(absoluteExecutable)) {
					return absoluteExecutable;
				} else if (File.separatorChar == '\\') {
					absoluteExecutable = Paths.get(pathElement, executable + ".exe");
					if (Files.isExecutable(absoluteExecutable) && Files.isRegularFile(absoluteExecutable)) {
						return absoluteExecutable;
					}
				}
			}

			return null;
		}

		private List<String> lines(InputStream input) throws IOException {
			try (var reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
				return reader.lines().collect(Collectors.toList());
			}
		}

		@Override
		public String toString() {
			return builder.command().stream().collect(Collectors.joining(" "));
		}
	}
}
