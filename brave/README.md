# brave-secondary-sampling

## Project Setup

### Gradle

```groovy
    repositories {
        // --snip--
        maven { url 'https://jitpack.io' }
    }
```

Depend on this:
```groovy
    implementation 'com.github.openzipkin-contrib.zipkin-secondary-sampling:brave-secondary-sampling:master-SNAPSHOT'
```

Exclude brave from transitive deps
```groovy
  // This is part is irrelevant if you don't use sleuth
  implementation("org.springframework.cloud:spring-cloud-starter-zipkin") {
    exclude group: "io.zipkin.brave" // use brave from brave-secondary-sampling
  }
```

### Maven
Setup this:
```xml
  <repositories>
    <repository>
      <id>jitpack.io</id>
      <url>https://jitpack.io</url>
    </repository>
  </repositories>
```

Depend on this:
```xml
  <dependency>
    <groupId>io.github.openzipkin-contrib.zipkin-secondary-sampling</groupId>
    <artifactId>brave-secondary-sampling</artifactId>
    <version>master-SNAPSHOT</version>
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
