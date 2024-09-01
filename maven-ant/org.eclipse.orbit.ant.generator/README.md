The Maven Central artifacts for Ant are not in a form directly consumable by the Eclipse Platform, so the following dependencies are not actually repackaged and redistributed:

https://github.com/eclipse-orbit/orbit-simrel/blob/e9b717363348e51663066a0437491f66e02b2fad/maven-bnd/tp/MavenBND.target#L2445-L2625

When the generator detects version changes to those artifacts, updating those versions above, there will also be new downloads avaialble here:

- https://dlcdn.apache.org/ant/binaries/

The Ant version in the launch configuration 

https://github.com/eclipse-orbit/orbit-simrel/blob/e9b717363348e51663066a0437491f66e02b2fad/maven-ant/org.eclipse.orbit.ant.generator/AntGenerator.launch#L20

and in the pom

https://github.com/eclipse-orbit/orbit-simrel/blob/e9b717363348e51663066a0437491f66e02b2fad/maven-ant/pom.xml#L17

should be changed to this new version in order to generate based on this newly-available version.

These version changes, including the pom.xml changes, can and should be tested locally:

- https://github.com/eclipse-orbit/orbit-simrel/blob/main/maven-ant/Maven%20Ant.launch

If this works, the changes can be commmited and then the job

- https://ci.eclipse.org/orbit/job/orbit-simrel-maven-ant/

can be used to produced repackaged jars here:

- https://repo.eclipse.org/content/repositories/orbit-approved-artifacts/org/eclipse/orbit/ant/

Note that the classes and correspondig sources from jai are deliberately excluded.

The results are consumed by the BND target here:

https://github.com/eclipse-orbit/orbit-simrel/blob/e9b717363348e51663066a0437491f66e02b2fad/maven-bnd/tp/MavenBND.target#L1393-L1470

The generator produces a list of jars into 

https://github.com/eclipse-orbit/orbit-simrel/blob/main/maven-ant/publish/Bundle-ClassPath.properties

If those contents change, they should be copied here to ensure that nothing is missed should something be changed in this regard:

https://github.com/eclipse-orbit/orbit-simrel/blob/e9b717363348e51663066a0437491f66e02b2fad/maven-bnd/tp/MavenBND.target#L1433-L1457