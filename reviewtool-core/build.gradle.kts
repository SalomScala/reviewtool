/*
 * Platform-independent core of CoRT, assembled from the pre-existing OSGi module
 * sources. None of these source directories depends on the Eclipse platform
 * (org.eclipse.jgit is the plain JGit library), so they can be reused unchanged
 * for the IntelliJ plugin.
 */
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    // the legacy sources produce a number of warnings that are not worth fixing right now
    options.compilerArgs.add("-nowarn")
}

sourceSets {
    main {
        java {
            setSrcDirs(
                listOf(
                    "../de.setsoftware.reviewtool.core.model/src",
                    "../de.setsoftware.reviewtool.reviewdata/src",
                    "../de.setsoftware.reviewtool.ordering/src",
                    "../de.setsoftware.reviewtool.changesources.git/src",
                    "../de.setsoftware.reviewtool.ticketconnectors.file/src",
                    "../de.setsoftware.reviewtool.ticketconnectors.jira/src",
                    "../de.setsoftware.reviewtool.ticketconnectors.youtrack/src",
                    "../de.setsoftware.reviewtool.core.cognitive/src",
                ),
            )
        }
    }
    test {
        java {
            setSrcDirs(
                listOf(
                    "../de.setsoftware.reviewtool.core.model.tests/src",
                    "../de.setsoftware.reviewtool.ordering.tests/src",
                    "../de.setsoftware.reviewtool.changesources.git.tests/src",
                ),
            )
        }
    }
}

dependencies {
    api("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    api("com.eclipsesource.minimal-json:minimal-json:0.9.5")
    // JavaParser is used by the cognitive support (change classification and stop ordering)
    api("com.github.javaparser:javaparser-core:3.9.1")
    // checked-in library used by the telemetry support
    implementation(files("../de.setsoftware.reviewtool.core.model/hackybuffer-1.0.jar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.hamcrest:hamcrest-core:1.3")
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

tasks.test {
    useJUnit()
    // isolate the tests from the developer's global git configuration, which may
    // contain settings (e.g. gpg.format=ssh) that the JGit version in use cannot parse
    val isolatedHome = layout.buildDirectory.dir("test-home")
    // some tests write cache files into the current working directory
    val testWorkDir = layout.buildDirectory.dir("test-workdir")
    doFirst {
        val homeDir = isolatedHome.get().asFile
        homeDir.mkdirs()
        // the git tests assume that newly initialized repositories use "main" as default branch
        homeDir.resolve(".gitconfig").writeText("[init]\n\tdefaultBranch = main\n")
        testWorkDir.get().asFile.mkdirs()
        workingDir = testWorkDir.get().asFile
    }
    environment("HOME", isolatedHome.get().asFile.absolutePath)
    environment("XDG_CONFIG_HOME", isolatedHome.get().asFile.resolve(".config").absolutePath)
    environment("GIT_CONFIG_NOSYSTEM", "1")
    systemProperty("user.home", isolatedHome.get().asFile.absolutePath)
    // the expected values in the git tests were written for this timezone
    systemProperty("user.timezone", "Europe/Berlin")
}
