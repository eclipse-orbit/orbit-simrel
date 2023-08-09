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
package org.eclipse.orbit.maven.bnd.generator;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Analyzer {
	private static final Pattern SITE_PATTERN = Pattern.compile("^\\s*<site>(?<body>(?<nl>\r?\n).*?)</site>",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern LOCATION_PATTERN = Pattern.compile("^\\s*<location.*?</location>",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern FEATURE_PATTERN = Pattern.compile("^\\s*<feature.*?</feature>",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern INSTRUCTIONS_PATTERN = Pattern.compile(
			"^\s*<instructions>\\s*<!--\\s+SHA1\\s+(?<sha>[^ ]*)\\s*-->\\s*<!\\[CDATA\\[\\s*(?<instructions>.*?)\\]\\]>\\s*</instructions>",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("^\\s*<dependency>\\s*" //
			+ "<groupId>(?<groupId>.*?)</groupId>\\s*" //
			+ "<artifactId>(?<artifactId>.*?)</artifactId>\\s*" //
			+ "<version>(?<version>.*?)</version>\\s*" //
			+ "<type>(?<type>.*?)</type>" //
			+ "(:?\\s*<classifier>(?<classifier>.*?)</classifier>)?.*?" //
			+ "</dependency>", //
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern BND_BUNDLE_VERSION_PATTERN = Pattern
			.compile("^Bundle-Version\\s*:\\s*.*?(?<qualifier>\\.v[0-9]+-[0-9]+|)$", Pattern.MULTILINE);

	private static final Pattern FEATURE_ID_PATTERN = Pattern.compile("id=\"(?<id>[^\"]+)\"",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern FEATURE_VERSION_PATTERN = Pattern.compile("version=\"(?<version>[^\"]+)\"",
			Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern FEATURE_VERSION_QUALIFIER_PATTERN = Pattern.compile(
			"\\s*<feature.*?version=\"[^\"]+?(?<qualifier>\\.v[-0-9]+||)\"", Pattern.MULTILINE | Pattern.DOTALL);

	boolean verbose;

	private String version;

	private Set<String> featureIDs = new TreeSet<>();

	private String year;

	private String qualifier;

	private Path target;

	private Path category;

	private Path categoryMinimal;

	public Analyzer(List<String> arguments) {
		verbose = arguments.contains("-verbose");
		version = getArgument(arguments, "-version");
		year = new SimpleDateFormat("yyyy").format(new Date());
		qualifier = new SimpleDateFormat("'v'yyyyMMdd-MMss").format(new Date());

		target = getPathArgument(arguments, "-target");
		category = getPathArgument(arguments, "-category");
		categoryMinimal = getPathArgument(arguments, "-category-minimal");
	}

	public void analyze() throws IOException {
		var currentTarget = Files.readString(target);
		var transformedTarget = visitTarget(currentTarget);
		Files.writeString(target, transformedTarget);
		if (verbose) {
			System.out.println("--- " + target.getFileName() + "---"
					+ (!currentTarget.equals(transformedTarget) ? " CHANGED" : ""));
			System.out.println(transformedTarget);
			System.out.println();
		}

		if (category != null) {
			var curentCategory = Files.readString(category);
			var transformedCategory = visitCategory(curentCategory);
			Files.writeString(category, transformedCategory);
			if (verbose) {
				System.out.println("--- " + category.getFileName() + "---"
						+ (!curentCategory.equals(transformedCategory) ? " CHANGED" : ""));
				System.out.println(transformedCategory);
			}
		}

		if (categoryMinimal != null) {
			featureIDs.removeIf(id -> id.contains("exclude"));
			var curentCategoryMinimal = Files.readString(categoryMinimal);
			var transformedCategoryMinimal = visitCategory(curentCategoryMinimal);
			Files.writeString(categoryMinimal, transformedCategoryMinimal);
			if (verbose) {
				System.out.println("--- " + categoryMinimal.getFileName() + "---"
						+ (!curentCategoryMinimal.equals(transformedCategoryMinimal) ? " CHANGED" : ""));
				System.out.println(transformedCategoryMinimal);
			}
		}
	}

	public static void main(String[] args) throws IOException {
		var arguments = new ArrayList<>(Arrays.asList(args));
		new Analyzer(arguments).analyze();
	}

	private String visitCategory(String category) {
		var matcher = SITE_PATTERN.matcher(category);
		matcher.find();
		var nl = matcher.group("nl");

		var body = new StringBuilder(nl);
		for (var featureID : featureIDs) {
			body.append("   <feature id=\"").append(featureID).append("\"/>").append(nl);
			body.append("   <feature id=\"").append(featureID).append(".source\"/>").append(nl);
		}

		return replace(category, matcher, "body", body.toString());
	}

	private String visitTarget(String target) {
		var result = new StringBuilder();
		var locationMatcher = LOCATION_PATTERN.matcher(target);
		while (locationMatcher.find()) {
			locationMatcher.appendReplacement(result, Matcher.quoteReplacement(visitLocation(locationMatcher.group())));
		}
		locationMatcher.appendTail(result);
		return result.toString();
	}

	private String visitLocation(String location) {
		var result = new StringBuilder();
		var featureMatcher = FEATURE_PATTERN.matcher(location);
		if (!featureMatcher.find()) {
			if (location.contains("type=\"InstallableUnit\"")) {
				return location;
			}
			throw new IllegalStateException("Each location must contain feature.");
		}

		String feature = featureMatcher.group();
		var featureID = getFeatureID(feature);
		if (featureID == null) {
			throw new IllegalStateException("Each feature must have an id.");
		}

		if (!featureIDs.add(featureID)) {
			throw new IllegalStateException("Each feature must have unique id: " + featureID);
		}

		featureMatcher.appendReplacement(result, Matcher.quoteReplacement(visitFeature(feature, featureID)));
		featureMatcher.appendTail(result);
		return visitDependencies(result.toString(), featureID);
	}

	private String getFeatureID(String feature) {
		var featureIDMatcher = FEATURE_ID_PATTERN.matcher(feature);
		if (featureIDMatcher.find()) {
			return featureIDMatcher.group("id");
		}
		return null;
	}

	private String visitFeature(String feature, String featureID) {
		var featureVersionMatcher = FEATURE_VERSION_PATTERN.matcher(feature);
		if (!featureVersionMatcher.find()) {
			throw new IllegalStateException("Each feature must have a version.");
		}
		var featureVersion = featureVersionMatcher.group("version");

		if (verbose) {
			System.out.println("--- " + featureID + ":" + featureVersion + "---");
		}

		if (!featureVersion.startsWith(version)) {
			feature = replace(feature, featureVersionMatcher, "version", version);
		}

		return feature.replaceAll("Copyright \\(c\\) [0-9]+", "Copyright (c) " + year);
	}

	private String visitDependencies(String location, String featureID) {
		var instructionsMatcher = INSTRUCTIONS_PATTERN.matcher(location);
		if (!instructionsMatcher.find()) {
			if (location.contains("missingManifest=\"error\"")) {
				return location;
			}
			if (featureID.contains(".exclude")) {
				return location;
			}

			throw new IllegalStateException("Each location must have BND instructions.");
		}

		var sha1 = instructionsMatcher.group("sha");
		var instructions = instructionsMatcher.group("instructions");

		var bndBundleVersionMatcher = BND_BUNDLE_VERSION_PATTERN.matcher(instructions);
		if (!bndBundleVersionMatcher.find()) {
			throw new IllegalStateException("Each BND instruction must have a Bundle-Version: header");
		}

		var dependencies = new StringBuilder();
		for (var dependencyMatcher = DEPENDENCY_PATTERN.matcher(location); dependencyMatcher.find();) {
			var groupId = dependencyMatcher.group("groupId");
			var artifactId = dependencyMatcher.group("artifactId");
			var artifactVersion = dependencyMatcher.group("version");
			var maven = groupId + ":" + artifactId + ":" + artifactVersion;
			dependencies.append(maven).append("\n");
		}

		// Compute the SHA1 from the dependency coordinates and the instructions without
		// the qualifier and compare it with the current value.
		//
		var digest = getDigest(dependencies + replace(instructions, bndBundleVersionMatcher, "qualifier", ""));
		if (!digest.equals(sha1)) {
			// Replace the qualifier with the new current qualifier.
			//
			var bundleVersionMatcherInLocation = BND_BUNDLE_VERSION_PATTERN.matcher(location);
			bundleVersionMatcherInLocation.find(instructionsMatcher.start("instructions"));
			location = replace(location, bundleVersionMatcherInLocation, "qualifier", "." + qualifier);

			// Replace the SHA1 digest with the new digest.
			//
			location = replace(location, instructionsMatcher, "sha", digest);

			// Replace the feature's qualifier with the new current qualifier.
			var featureVersionQualifierMatcher = FEATURE_VERSION_QUALIFIER_PATTERN.matcher(location);
			featureVersionQualifierMatcher.find();
			location = replace(location, featureVersionQualifierMatcher, "qualifier", "." + qualifier);
		}

		return location;
	}

	private static String replace(String string, Matcher matcher, String group, String replacement) {
		return string.subSequence(0, matcher.start(group)) + replacement
				+ string.subSequence(matcher.end(group), string.length());
	}

	private String getDigest(String instructions) {
		var normalized = instructions.trim().replace("\r\n", "\n").replace("\n\\", "\n").replaceAll("\n\n+", "\n")
				.replaceAll("[ \t][ \t]+", " ");

		if (verbose) {
			System.out.println("--- digest basis ---");
			System.out.println(normalized);
			System.out.println();
		}

		try {
			var digest = MessageDigest.getInstance("SHA-1");
			digest.update(normalized.getBytes(StandardCharsets.UTF_8));
			return String.format("%040x", new BigInteger(1, digest.digest()));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private Path getPathArgument(List<String> arguments, String name) {
		String argument = getArgument(arguments, name);
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
}
