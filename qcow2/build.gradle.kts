plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/** Runs the engine CLI, e.g. `./gradlew :qcow2:qcow2Cli --args="info /tmp/disk.qcow2"`. */
val qcow2Cli by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the pure Kotlin qcow2 engine CLI (info / verify / create / write / stats)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.xis3794.mirrorbox.qcow2.tools.Qcow2Cli")
}
