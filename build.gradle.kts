plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
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
