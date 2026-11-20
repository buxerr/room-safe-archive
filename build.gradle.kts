plugins {
    kotlin("jvm") version "2.4.21"
    `maven-publish`
}

group = "com.github.buxerr"
// JitPack sets VERSION to the Git tag being built (for example, v0.1.0).
version = providers.environmentVariable("VERSION").orElse("0.1.0").get()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// The test source is a dependency-free executable harness, run by archiveChecks below.
tasks.named("test") {
    enabled = false
}

tasks.register<JavaExec>("archiveChecks") {
    group = "verification"
    description = "Runs the archive security and recovery checks."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.buxerr.roomarchive.RoomArchiveTest"
}

tasks.check {
    dependsOn("archiveChecks")
}
