# Eclipse Orbit SimRel

## Managing 3rd Party Dependencies

This repository provides infrastructure for managing 3rd party dependencies based on libraries hosted at [Maven Central](https://repo1.maven.org/maven2/).
It augments the Eclipse Bundle Recipe [EBR](https://github.com/eclipse-orbit/ebr/#readme) infrastructure 
traditionally used by [Orbit](https://github.com/eclipse-orbit/orbit/#readme).
Many libraries hosted at Maven central are already provided in the form of [OSGi](https://www.osgi.org/) bundles and can simply be reused as is,
but many others need to be repackaged as bundles.
Orbit has traditionally been repackaging all libraries from Maven Central using EBR, which is based on [BND](https://bnd.bndtools.org/).

Eclipse [m2e](https://projects.eclipse.org/projects/technology.m2e) provides extensions to Eclipse [PDE](https://projects.eclipse.org/projects/eclipse.pde)
that allow dependencies on Maven artifacts to be expressed directly in a target platform file,
including the ability to wrap a non-OSGi jar as an OGI-bundle using BND instructions.
This mechanism is also directly supported by [Tycho](https://projects.eclipse.org/projects/technology.tycho).
These technologies are utilized by Orbit SimRel to evolve and modernize Orbit's infrastructure.


### Maven OSGi

The `maven-osgi` folder provides infrastructure for analyzing, updating, and aggregating Maven dependencies in PDE target platform files
and is used to produce a p2 update site hosting PGP-signed Maven libraries that are directly available as OSGi bundles.

The corresponding [readme](maven-osgi/README.md) provides details.


### Maven BND

The `maven-bnd` folder provides infrastructure for wrapping non-OSGi Maven dependencies as OSGi bundles utilizing PDE/Tycho
and is used to produce a p2 update site hosting jar-signed Maven libraries wrapped as OSGi bundles.

The corresponding [readme](maven-bnd/README.md) provides details.


### Orbit Aggregation

The `orbit-aggregation` folder provides infrastructure for aggregating a p2 repository from the following:

- Traditional EBR-wrapped OSGi bundles.
- Maven direct OSGi bundles.
- Maven BND-wrapped OSGi bundles
- Legacy OSGi bundles required by the above.

The corresponding [readme](orbit-aggregation/README.md) provides details.


## Contributing

Contributions are welcome.

The [contribution guide](CONTRIBUTING.md) provides details.