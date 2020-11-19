# brave-secondary-sampling

[![Maven Central](https://img.shields.io/maven-central/v/io.zipkin.contrib.zipkin-secondary-sampling/brave-secondary-sampling.svg)](https://search.maven.org/search?q=g:io.zipkin.contrib.zipkin-secondary-sampling%20AND%20a:brave-secondary-sampling)

## Project Setup

### Gradle

Depend on this:
```groovy
    implementation 'com.github.openzipkin-contrib.zipkin-secondary-sampling:brave-secondary-sampling:CURRENT_RELEASE'
```

Exclude brave from transitive deps
```groovy
  // This is part is irrelevant if you don't use sleuth
  implementation("org.springframework.cloud:spring-cloud-starter-zipkin") {
    exclude group: "io.zipkin.brave" // use brave from brave-secondary-sampling
  }
```

### Maven

Depend on this:
```xml
  <dependency>
    <groupId>io.zipkin.contrib.zipkin-secondary-sampling</groupId>
    <artifactId>brave-secondary-sampling</artifactId>
    <version>CURRENT_RELEASE</version>
  </dependency>
```

Exclude brave from transitive deps
```xml
  <!-- This is part is irrelevant if you don't use sleuth -->
  <dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-zipkin</artifactId>
    <exclusions>
      <exclusion>
        <!-- use brave from brave-secondary-sampling -->
        <groupId>io.zipkin.brave</groupId>
        <artifactId>*</artifactId>
      </exclusion>
    </exclusions>
  </dependency>
```

TODO more instructions

## Artifacts
All artifacts publish to the group ID "io.zipkin.contrib.zipkin-secondary-sampling". We use a common
release version for all components.

### Library Releases
Releases are at [Sonatype](https://oss.sonatype.org/content/repositories/releases) and [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.zipkin.contrib.zipkin-secondary-sampling%22)

### Library Snapshots
Snapshots are uploaded to [Sonatype](https://oss.sonatype.org/content/repositories/snapshots) after
commits to master.
