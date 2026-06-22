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
package org.eclipse.orbit.maven.generator;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

public class SourceBundleChecker {
	public static void main(String[] args) throws IOException {
		var path = Path.of(args[0]);
		try (var paths = Files.list(path)) {
			for (var bundle : paths.toList()) {
				if (bundle.getFileName().toString().contains(".source_")) {
					// System.err.println(bundle);
					try (var sourceFileSystem = FileSystems.newFileSystem(bundle)) {
//						var foo = sourceFileSystem.getPath("META-INF/MANIFEST.MF");
//						try (var inputStream = Files.newInputStream(foo)) {
//							Manifest manifest = new Manifest(inputStream);
//							Attributes attributes = manifest.getAttributes("Bundle-SymbolicName");
//							Attributes mainAttributes = manifest.getMainAttributes();
//							String bsn = mainAttributes.getValue("Bundle-SymbolicName");
//							String exportedPackages = mainAttributes.getValue("Export-Package");
//							if (exportedPackages != null) {
//								// System.err.println(bsn.toString());
//								manifest.write(System.err);
//							}
//						}

						var services = sourceFileSystem.getPath("META-INF/services");
						if (Files.isDirectory(services)) {
							System.out.println(bundle.getFileName());
							try (var servicePath = Files.list(services)) {
								for (var serviceFile : servicePath.toList()) {
									System.out.println("  " + serviceFile);
									for (var line : Files.readAllLines(serviceFile)) {
										if (!line.startsWith("#") && !line.isBlank()) {
											System.out.println("    " + line);
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}
}
