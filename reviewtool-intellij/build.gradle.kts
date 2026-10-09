/*
 * IntelliJ plugin for CoRT. Uses the IntelliJ Platform Gradle Plugin (2.x), see
 * https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
 *
 * Note that building this module downloads the IntelliJ platform from the JetBrains
 * servers, so it needs network access to *.jetbrains.com.
 */
plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":reviewtool-core"))

    intellijPlatform {
        intellijIdeaCommunity("2024.3.5")
    }
}

intellijPlatform {
    // name of the plugin folder and of the zip in build/distributions
    projectName = "CoRTOriginal"

    pluginConfiguration {
        id = "de.setsoftware.reviewtool.cortoriginal"
        name = "CoRTOriginal"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

// Gives the extensions in the packaged plugin.xml their own IDs, so that the plugin can be
// installed next to another CoRT plugin (the source plugin.xml stays unchanged).
tasks.patchPluginXml {
    doLast {
        val file = outputFile.get().asFile
        val replacements = mapOf(
            "<toolWindow\\s+id=\"CoRT\"" to "<toolWindow id=\"CoRTOriginal\"",
            "id=\"de\\.setsoftware\\.reviewtool\\.intellij\\.settings\"" to "id=\"de.setsoftware.reviewtool.cortoriginal.settings\"",
            "displayName=\"Code Review Tool \\(CoRT\\)\"" to "displayName=\"Code Review Tool (CoRTOriginal)\"",
        )
        var text = file.readText()
        for ((pattern, replacement) in replacements) {
            val regex = Regex(pattern)
            check(regex.containsMatchIn(text)) { "plugin.xml does not contain $pattern" }
            text = regex.replace(text, replacement)
        }
        file.writeText(text)
    }
}
