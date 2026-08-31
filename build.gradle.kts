// FIRST line, and it has to be. The shadowJar verification block at the bottom opens the
// built archive, and inside `tasks { shadowJar { } }` the Kotlin DSL resolves the bare
// name `java` to the JavaPluginExtension accessor rather than to the root package - so a
// fully-qualified `java.util.zip.ZipFile(...)` there fails to compile with "Unresolved
// reference: util". A script-compilation failure fails every job in the workflow, not
// just the one that would have run the check, so this import is load-bearing.
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
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

// Declared once. The compile target and the test classpath must never drift apart,
// which is what the compile-target comment below exists to prevent, and two literals
// let a one-sided bump do exactly that.
val spigotApi = "org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT"

// The Adventure line, and the two literals must move together. adventure-platform-bukkit
// 4.4.1 resolves adventure-api 4.21.0 - read off its published pom - so MiniMessage has to
// be 4.21.0 and not the newest release. The current MiniMessage is 5.2.0, which resolves
// adventure-api 5.2.0; Gradle would then hand BukkitAudiences an API it was not compiled
// against and the first message send would throw NoSuchMethodError, with nothing at build
// time to catch it. The BOM below is what makes that alignment a declaration rather than a
// coincidence.
val adventureApi = "4.26.1"
val adventurePlatform = "4.4.1"

// 0.5.1 and NOT 0.5.2, which also exists on repo.tcoded.com with a full checksum set.
// 0.5.2 has no GitHub release and no git tag, and maven-metadata.xml still declares 0.5.1
// as <release>. The two are not byte-identical - same entry names, but FoliaLib.class
// differs - and what 0.5.2 adds is a better relocation diagnostic: it appends the jar's
// CodeSource path to the SEVERE "not relocated correctly" message. Useful, not worth the
// provenance. Revisit when it is tagged upstream.
val foliaLib = "com.tcoded:FoliaLib:0.5.1"

dependencies {
    // Spigot's API, NOT paper-api. Compiling against paper-api would let a Paper-only
    // method compile and then fail at runtime on Spigot with NoSuchMethodError,
    // undetected until the smoke matrix. This makes the compiler the gate instead, which
    // is what makes the README's Spigot claim enforceable. 1.20.4 is in the 1.20
    // series declared as api-version in plugin.yml.
    compileOnly(spigotApi)

    // implementation, NOT compileOnly, and this is the first place in this repository
    // where the difference is load-bearing. shadowJar bundles the runtimeClasspath;
    // compileOnly dependencies are not on it, so a compileOnly FoliaLib compiles clean and
    // then throws NoClassDefFoundError on the first line of onEnable, on every platform,
    // with nothing before the boot leg to catch it. The doLast block on shadowJar asserts
    // named entries are PRESENT partly to catch exactly that slip.
    implementation(platform("net.kyori:adventure-bom:$adventureApi"))
    implementation(foliaLib)
    implementation("net.kyori:adventure-platform-bukkit:$adventurePlatform")
    implementation("net.kyori:adventure-text-minimessage")

    // Do NOT exclude adventure-text-serializer-legacy from the shade. It arrives
    // transitively through adventure-platform-bukkit, is relocated by the net.kyori rule
    // along with everything else, and is what the two call sites Adventure cannot reach -
    // AsyncPlayerPreLoginEvent#disallow and Player#kickPlayer, both String-only on
    // spigot-api - will need when enforcement lands. A size-trimming pass that drops it
    // would break that with no build-time signal.

    // Unit testing.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // compileOnly dependencies are not inherited by the test compile classpath, and
    // YamlConfiguration runs standalone with no server instance, so the test suite needs
    // its own copy to cover configuration parsing from the configuration issue onward.
    testImplementation(spigotApi)
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

        // Test sources exist as of the scheduler seam, so this task now actually runs and
        // the configuration below is live. Gradle 9 is here and Test.failOnNoDiscoveredTests
        // does default to true - read back off the live task on 9.7.1, not assumed - so a
        // test source set that compiles but discovers nothing now fails instead of passing
        // silently. It is satisfied: 145 tests are discovered and run. Read the counts, not
        // the exit code, whenever the JUnit platform or its engine moves.
        //
        // A skipped test must fail the build, because in this project skipping is not
        // usually a choice. Copied from SpiralGenesis, where MockBukkit's
        // UnimplementedOperationException extends TestAbortedException and so reports as
        // SKIPPED while the build still succeeds - indistinguishable from coverage, which
        // is the one thing test results are for.
        //
        // addTestListener is still present and still un-deprecated on Gradle 9.7.1 -
        // checked by reflection against the live task and by a full `--warning-mode all`
        // build, which reports no deprecation for it. If it ever does go, whatever
        // replaces it must keep this exact guarantee: a SKIPPED test fails the build.
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

    // shadowJar owns the canonical archive name, so the thin jar needs its own. Still
    // required on shadow 9, but for a different reason than on shadow 8, and the old one
    // is now wrong: shadow 9 makes `assemble` depend on :shadowJar, so `assemble` no
    // longer leaves a thin jar behind where the plugin jar belongs. What it does instead
    // is run :jar AND :shadowJar in the same invocation, and without this classifier both
    // declare the identical output path - two tasks writing one file, with only task
    // ordering deciding which one survives. Verified by removing the classifier on 9.6.1:
    // the build still succeeds and :shadowJar happens to run second, which is precisely
    // the kind of accident that holds until it does not.
    jar {
        archiveClassifier.set("thin")
    }

    shadowJar {
        archiveBaseName.set("SessionPulse")
        archiveClassifier.set("")

        // DO NOT enable minimize(). This is not a size-versus-effort tradeoff that a
        // future pass may reconsider - it is a correctness bug, and it would surface only
        // on a running server.
        //
        // FoliaLib picks its platform implementation reflectively:
        // FoliaLib#createServerImpl builds the class name from its own package plus
        // ".impl." plus a String taken from ImplementationType, and calls Class.forName.
        // Read the constant pool: FoliaImplementation, SpigotImplementation and the rest
        // appear ONLY as String literals, with zero bytecode references anywhere. So
        // minimize()'s reachability analysis sees every one of them as unreachable and
        // strips them, and the plugin then dies on enable with FoliaLib's own
        // IllegalStateException - on all four platforms, with no build-time signal.
        //
        // Adventure has the same shape: adventure-text-serializer-gson registers its
        // providers through META-INF/services, which is a reflective edge minimize()
        // cannot follow either.
        //
        // If size ever genuinely matters, the entry cost is
        // `exclude(dependency("com.tcoded:FoliaLib"))` plus the same for every Adventure
        // module that publishes a service file - and a full boot on all four platforms to
        // prove it. Nothing less.

        // REQUIRED, and easy to leave out because nothing fails at build time without it.
        // shadow registers NO transformers by default - true on 8.3.6 and still true on
        // 9.6.1 - and mergeServiceFiles() is the only thing that adds
        // ServiceFileTransformer. Without it the two service files in
        // adventure-text-serializer-gson
        //   META-INF/services/net.kyori...JSONComponentSerializer$Provider
        //   META-INF/services/net.kyori...DataComponentValueConverterRegistry$Provider
        // are copied verbatim: their file NAME and their CONTENT keep the original
        // net.kyori package while the classes they name have moved. Adventure's
        // ServiceLoader lookup then finds nothing and throws at message-send time, not at
        // enable. ServiceFileTransformer runs every relocator over both the path and each
        // line of the body, which is exactly what is needed. Verified empirically here.
        //
        // The duplicatesStrategy line is shadow 9's addition and is not cosmetic. shadow 9
        // defaults shadowJar to EXCLUDE and then warns, by name, that a file matched by a
        // transformer under EXCLUDE may have its duplicates dropped BEFORE the transformer
        // ever sees them - which for ServiceFileTransformer means a second module's copy of
        // a service file is discarded rather than merged into the first. Today each of the
        // two Adventure service files comes from one artifact, so nothing is being dropped:
        // setting INCLUDE produced a byte-for-byte identical entry list, 843 entries either
        // way. It is set so that the day a second module publishes the same service file,
        // the transformer merges it instead of the copy task silently winning.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()

        // -------------------------------------------------------------------------
        // Relocation. The org's first, so the reasoning is recorded rather than assumed.
        // -------------------------------------------------------------------------
        //
        // com.tcoded.folialib -> ...lib.folialib
        //   Not about Paper. FoliaLib is bundled by many plugins, and two plugins shipping
        //   the same unrelocated com.tcoded.folialib means whichever loads first wins for
        //   both. FoliaLib knows this and says so itself: it logs SEVERE "FoliaLib is not
        //   relocated correctly!" when its own runtime package still begins
        //   com.tcoded.folialib. It stores that prefix as the literal "com,tcoded,folialib,"
        //   and replaces the commas at runtime precisely so that a shading tool's
        //   string-constant remapping cannot quietly rewrite the check into passing. That
        //   makes the absence of the warning in a server log a real runtime proof rather
        //   than a tautology.
        //
        // net.kyori -> ...lib.kyori
        //   The WHOLE net.kyori prefix, and deliberately wider than the two artifacts #19
        //   names. Resolution pulls 17 net.kyori modules. Relocating only
        //   adventure-platform-bukkit and adventure-text-minimessage would leave
        //   adventure-api, adventure-key, adventure-nbt, the serializers,
        //   adventure-platform-{api,facet} and net.kyori.examination sitting unrelocated at
        //   net.kyori, alongside the copy Paper ships natively - which is the exact
        //   collision the relocation exists to prevent.
        //
        //   The consequence is the constraint the notifier issue turns into a rule: our
        //   Component is NOT Paper's Component, so player.sendMessage(Component) is a
        //   runtime landmine on Paper. Every output call goes through BukkitAudiences, on
        //   all four platforms.
        //
        //   net.kyori.examination and net.kyori.option fall under this single rule; do not
        //   add rules for them. adventure-platform-bukkit 4.4.1 carries no dotted
        //   "net.kyori" string constants at all, so there is no reflective lookup for
        //   relocation to corrupt - that was a real bug once, fixed upstream in 4.3.4.
        //
        // NOT relocated: com.google.gson. adventure-text-serializer-gson excludes it from
        // its own pom and expects the server to provide it, which every CraftBukkit-derived
        // server does. There is no gson in this jar to relocate.
        relocate("com.tcoded.folialib", "com.ninja6.sessionpulse.lib.folialib")
        relocate("net.kyori", "com.ninja6.sessionpulse.lib.kyori")

        // -------------------------------------------------------------------------
        // Prove the relocation happened, rather than trusting that it did.
        // -------------------------------------------------------------------------
        // A relocation that silently did not apply produces a jar that builds green,
        // uploads green, and then either collides with another plugin's FoliaLib or picks
        // up Paper's Adventure - none of which the build would otherwise notice. So the
        // jar is opened and read.
        //
        // This is a doLast on shadowJar, so it runs when shadowJar runs. A second `build`
        // with nothing changed reports :shadowJar UP-TO-DATE and prints nothing; that is
        // fine, because CI always builds clean. Do not read it as "checked on every build".
        doLast {
            val jar = archiveFile.get().asFile
            ZipFile(jar).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()

                val leaked = names.filter {
                    it.startsWith("com/tcoded/") || it.startsWith("net/kyori/")
                }
                if (leaked.isNotEmpty()) {
                    throw GradleException(
                        "Relocation did not apply. ${leaked.size} entry(s) still carry an " +
                            "original package:" + System.lineSeparator() + "  - " +
                            leaked.take(10).joinToString(System.lineSeparator() + "  - ")
                    )
                }

                fun requireEntry(entry: String) {
                    if (entry !in names) {
                        throw GradleException("Expected relocated entry missing from the jar: $entry")
                    }
                }
                // Positive assertions, because "no com/tcoded entries" is also true of a jar
                // that bundled nothing at all - which is exactly what a compileOnly slip
                // produces.
                requireEntry("com/ninja6/sessionpulse/lib/folialib/FoliaLib.class")
                requireEntry("com/ninja6/sessionpulse/lib/folialib/impl/FoliaImplementation.class")
                requireEntry("com/ninja6/sessionpulse/lib/folialib/impl/SpigotImplementation.class")
                requireEntry("com/ninja6/sessionpulse/lib/kyori/adventure/text/Component.class")
                requireEntry("com/ninja6/sessionpulse/lib/kyori/adventure/platform/bukkit/BukkitAudiences.class")
                requireEntry("com/ninja6/sessionpulse/lib/kyori/adventure/text/minimessage/MiniMessage.class")

                // The service files are the half that fails silently at runtime, so they are
                // checked by name AND by content.
                val services = names.filter { it.startsWith("META-INF/services/") }
                if (services.isEmpty()) {
                    throw GradleException(
                        "No META-INF/services entries in the jar at all. Adventure ships two; " +
                            "their absence means the shade dropped them."
                    )
                }
                val unrelocated = services.filter { it.contains("net.kyori") }
                if (unrelocated.isNotEmpty()) {
                    throw GradleException(
                        "META-INF/services entries were not relocated - mergeServiceFiles() is " +
                            "missing or ineffective: $unrelocated"
                    )
                }
                services.forEach { path ->
                    val body = zip.getInputStream(zip.getEntry(path)).bufferedReader().readText()
                    if (body.contains("net.kyori")) {
                        throw GradleException(
                            "Service file $path still names an unrelocated class:" +
                                System.lineSeparator() + body
                        )
                    }
                }
            }
            logger.lifecycle("Relocation verified in ${jar.name}.")
        }
    }

    // Redundant on shadow 9, and kept deliberately rather than by inertia: shadow 9 wires
    // :assemble -> :shadowJar itself, and :build depends on :assemble, so `./gradlew build`
    // reaches shadowJar with or without this line. It stays as the explicit statement that
    // a `build` which did not produce the shaded jar is not a build of this project - a
    // guarantee currently held by a third-party plugin's task wiring, one minor release
    // away from moving.
    build {
        dependsOn(shadowJar)
    }
}
