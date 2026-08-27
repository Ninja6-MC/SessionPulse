plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.ninja6.sessionpulse"
version = project.findProperty("pluginVersion")?.toString() ?: "0.1.0-SNAPSHOT"
description = "Gentle session health reminders, playtime tracking, and optional session " +
    "limits for PaperMC, Spigot and Folia"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    // PaperMC. Listed before the Spigot nexus deliberately: repo.papermc.io's
    // maven-public group proxies org.spigotmc, so spigot-api still resolves when
    // hub.spigotmc.org is down.
    maven("https://repo.papermc.io/repository/maven-public/")
    // Spigot's own nexus - the origin for spigot-api.
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    // FoliaLib. Not on Maven Central; declared now so the scheduler issue does not have
    // to touch this block again.
    maven("https://repo.tcoded.com/releases")
}

dependencies {
    // Spigot's API, NOT paper-api. Compiling against paper-api would let a Paper-only
    // method compile and then fail at runtime on Spigot with NoSuchMethodError,
    // undetected until the smoke matrix. This makes the compiler the gate instead, which
    // is what makes the README's Spigot claim enforceable. 1.20.4 matches the
    // api-version declared in plugin.yml.
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")

    // Unit testing.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // compileOnly dependencies are not inherited by the test compile classpath, and
    // YamlConfiguration runs standalone with no server instance, so the test suite needs
    // its own copy to cover configuration parsing from the configuration issue onward.
    testImplementation("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        val props = mapOf(
            "name" to rootProject.name,
            "version" to project.version,
            "description" to project.description,
            "apiVersion" to "1.20"
        )
        inputs.properties(props)
        // expand() is Groovy's SimpleTemplateEngine: it treats EVERY `$` in a matched
        // file as a placeholder. Keep config.yml and any other resource carrying a
        // MiniMessage payload out of this pattern, or a literal `$` fails the build.
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    javadoc {
        // Prose docs are intentional here; missing @param/@return on self-describing
        // accessors should not flood the build log.
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }

        // There are no test sources yet, so this task reports NO-SOURCE and none of the
        // configuration below runs. Gradle 9 adds Test.failOnNoDiscoveredTests, defaulting
        // to true: it does not apply to a NO-SOURCE task, but it does apply the moment a
        // test source file exists that discovers nothing. Whoever bumps this wrapper past
        // 8.x, or adds the first test file, should re-run `./gradlew test` deliberately.
        //
        // A skipped test must fail the build, because in this project skipping is not
        // usually a choice. Copied from SpiralGenesis, where MockBukkit's
        // UnimplementedOperationException extends TestAbortedException and so reports as
        // SKIPPED while the build still succeeds - indistinguishable from coverage, which
        // is the one thing test results are for.
        val skipped = mutableListOf<String>()
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
            override fun beforeTest(testDescriptor: TestDescriptor) {}
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                if (result.resultType == TestResult.ResultType.SKIPPED) {
                    skipped += "${testDescriptor.className}.${testDescriptor.displayName}"
                }
            }
        })
        doLast {
            if (skipped.isNotEmpty()) {
                throw GradleException(
                    skipped.joinToString(
                        separator = System.lineSeparator() + "  - ",
                        prefix = "Tests were skipped rather than run, which this build treats as "
                            + "failure. If a mock operation is unimplemented, substitute a seam; "
                            + "do not let the test abort." + System.lineSeparator() + "  - "
                    )
                )
            }
        }
    }

    shadowJar {
        archiveBaseName.set("SessionPulse")
        archiveClassifier.set("")
        // Nothing is bundled yet - the only dependency is compileOnly - so minimize()
        // would have nothing to strip. The FoliaLib and Adventure relocations go here.
    }

    build {
        dependsOn(shadowJar)
    }
}
