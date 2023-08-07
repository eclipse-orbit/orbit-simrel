# Reports


## Maven OSGi Reports

The following reports are generated from the target platform file of the each corresponding [SimRel](https://ci.eclipse.org/simrel/) participant
as well as from the locally-defined [supplement](../maven-osgi/tp/other/MavenSupplement.target).

<!-- maven-osgi -->

- [cdt](maven-osgi/cdt/REPORT.md)
- [egit](maven-osgi/egit/REPORT.md)
- [linuxtools](maven-osgi/linuxtools/REPORT.md)
- [m2e](maven-osgi/m2e/REPORT.md)
- [mylyn-docs](maven-osgi/mylyn-docs/REPORT.md)
- [passage](maven-osgi/passage/REPORT.md)
- [platform](maven-osgi/platform/REPORT.md)
- [supplement](maven-osgi/supplement/REPORT.md)
- [tm4e](maven-osgi/tm4e/REPORT.md)
- [windowbuilder](maven-osgi/windowbuilder/REPORT.md)
- [wwd](maven-osgi/wwd/REPORT.md)

<!-- maven-osgi -->

The generator copies each *.target file extracting the Maven dependencies from each.
It consults the metadata available at Maven Central for any available updated versions for each of dependency.
From that information, it generate each of the above reports.

In addition, the generator merges all the dependencies in order to generate the following overall merged-target report:

- [merged-target](maven-osgi/merged-target/REPORT.md)

The generator updates [Maven.target](../maven-osgi/tp/Maven.target) with any available minor version updates and adds any missing dependencies.


## Maven BND Report

Following report is generated for the content of [MavenBND.target](../maven-bnd/tp/MavenBND.target):

- [bnd-target](maven-bnd/merged-target/REPORT.md)

The generator updates it with any available minor version updates.

## Maven Sign Report

Following report is generated for the content of [MavenSign.target](../maven-sign/tp/MavenSign.target):

- [sign-target](maven-sign/merged-target/REPORT.md)

The generator updates it with any available minor version updates.
