plugins {
    id("java")
    alias(libs.plugins.quarkus)
    id("io.fabric8.java-generator") version "7.0.1"
}

group = "io.okd.operators.controller"
version = "1.0.0-SNAPSHOT"
description = "Triggers builds of operators based on recipes provided"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kubernetes")
    implementation("io.quarkus:quarkus-container-image-jib")
    implementation("io.quarkus:quarkus-openshift-client")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkiverse.jgit:quarkus-jgit:3.3.3")

    // Lombok
    val lombok = "org.projectlombok:lombok:1.18.36"
    compileOnly(lombok)
    annotationProcessor(lombok)
}

javaGen {
    source = file("src/main/crds")
    alwaysPreserveUnknown = true
    config
}

sourceSets {
    main {
        java {
            srcDir("build/generated/sources")
        }
    }
}