# General

This folder contains a generalized [pom.xml](pom.xml) that can be reused for deploying artifacts to [repo.eclipse.org](https://repo.eclipse.org/content/repositories/orbit-approved-artifacts/org/eclipse/orbit/).

# MavenAxis.jenkinsfile

This does a Maven build of [https://github.com/apache/axis-axis1-java](https://github.com/apache/axis-axis1-java).
It's effectively a SNAPSHOT build, so a build timestamp is used as the version qualifier to produce a unique *release* for each build.
The build hard-codes the expected version **1.4.1** which will need updating if ever it's incremented in the future.
It removes the `META-INF/maven/*` folder from the jars to ensure that a new, clean, minimal pom.xml is generated,
and it also adds a `Eclipse-SourceReferences:` header so that the commit ID of the GitGub repository is traceable.
