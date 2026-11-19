plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
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
