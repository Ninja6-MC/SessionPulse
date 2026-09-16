// FIRST line, and it has to be. The shadowJar verification block at the bottom opens the
// built archive, and inside `tasks { shadowJar { } }` the Kotlin DSL resolves the bare
// name `java` to the JavaPluginExtension accessor rather than to the root package - so a
// fully-qualified `java.util.zip.ZipFile(...)` there fails to compile with "Unresolved
// reference: util". A script-compilation failure fails every job in the workflow, not
// just the one that would have run the check, so this import is load-bearing.
import java.util.zip.ZipFile
import io.papermc.hangarpublishplugin.PageSyncTask
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
    // Hangar has no publish API that a generic action can drive, so publication goes
    // through PaperMC's own Gradle plugin; Modrinth is published from the workflow. The
    // same plugin and version as SpiralGenesis. Applying it registers
    // publishPluginPublicationToHangar and the resource page sync tasks below: no task of
    // an ordinary build or test depends on them, and the API token is read lazily, only when
    // one of them runs.
    id("io.papermc.hangar-publish-plugin") version "0.1.4"
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
    // GeyserMC / opencollab, for MCProtocolLib and nothing else. Last on purpose: only the
    // botClient source set asks for anything published here, so the plugin's own
    // configurations resolve from the repositories above and `./gradlew build` never
    // depends on this host being up. The smoke legs build the bot fixture themselves.
    maven("https://repo.opencollab.dev/main/")
}

// The protocol bot the smoke test drives a real player connection with. Its own source
// set rather than src/test, so it compiles to a runnable jar and can never be picked up
// by shadowJar and shipped. Copied from SpiralGenesis's botClient.
sourceSets {
    create("botClient")
}

// Declared once. The compile target and the test classpath must never drift apart,
// which is what the compile-target comment below exists to prevent, and two literals
// let a one-sided bump do exactly that.
val spigotApi = "org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT"

// The Adventure line: two literals, and they need not be equal. adventure-platform's line
// ends at 4.4.1, built against adventure-api 4.21.0 - read off its published pom - and the
// BOM below raises every adventure-api module to the version on the first line. Adventure
// keeps binary compatibility within 4.x, so that skew is permitted, and Dependabot's
// major-only ignore allows it by design; that is how 4.26.1 arrived. A MAJOR is the danger:
// adventure-api 5.x under BukkitAudiences 4.4.1 compiles and enables, and the first send
// throws NoSuchMethodError, AbstractMethodError or IncompatibleClassChangeError, with
// nothing at build time to catch it. The boot legs grep for all three, on the console path
// only; nothing automated exercises the player facets. The BOM is what makes the alignment
// a declaration rather than a coincidence.
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
    // along with everything else, and is what Notifier#legacy renders with, for the two
    // call sites Adventure cannot reach - AsyncPlayerPreLoginEvent#disallow and
    // Player#kickPlayer, both String-only on spigot-api. A size-trimming pass that drops it
    // fails the requireEntry below.

    // Unit testing.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // compileOnly dependencies are not inherited by the test compile classpath, and
    // YamlConfiguration runs standalone with no server instance, so the test suite needs
    // its own copy to cover configuration parsing from the configuration issue onward.
    testImplementation(spigotApi)

    // A real protocol client for the smoke test, pinned to the one server version it
    // speaks. A SNAPSHOT, so it can drift under us: when a smoke leg goes red on a bot
    // decode error rather than an assertion, suspect this line before the plugin.
    "botClientImplementation"("org.geysermc.mcprotocollib:protocol:1.21.11-SNAPSHOT")
}

// The Minecraft versions a release declares, on Hangar here and on Modrinth in
// .github/workflows/release.yml. The two lists are the same list and must be edited
// together.
//
// Explicit, not `1.20.x`/`1.21.x` or a range. api-version in plugin.yml is 1.20 and the
// compile target is 1.20.4, so 1.20.0-1.20.3 are not claimed; and the boot legs in ci.yml
// cover 1.20.4 and 1.21.11, so nothing newer than 1.21.11 is. 26.x in particular is NOT
// claimed: it refuses to boot below Java 25 and adventure-platform-bukkit 4.4.1 predates
// it, which ci.yml records as unmeasured. Widening this list is a compatibility claim and
// belongs with the evidence for it, not with a release.
//
// Comma-separated because Hangar's platformVersions is a List<String> and the property
// has to arrive as one string; it is split below exactly as SpiralGenesis splits it.
val releaseGameVersions =
    "1.20.4,1.20.5,1.20.6,1.21,1.21.1,1.21.2,1.21.3,1.21.4,1.21.5,1.21.6,1.21.7,1.21.8," +
        "1.21.9,1.21.10,1.21.11"

// Hangar publication. Every value is a property or an environment variable so the
// release workflow can set it per tag, and nothing here runs during a build or a test:
// `./gradlew tasks --all` lists publishPluginPublicationToHangar with no token present.
//
// The workflow picks the channel per tag, per the tier table in RELEASE_PROCESS.md, as
// SpiralGenesis does: Alpha for alpha tags, Beta for beta and rc tags, Release for stable
// tags. The default below applies only to a local invocation without -PhangarChannel.
hangarPublish {
    publications.register("plugin") {
        // The workflow passes the HANGAR_PROJECT repository variable, or SessionPulse.
        id.set(providers.gradleProperty("hangarProject").orElse("SessionPulse"))
        version.set(project.version.toString())
        // Hangar channel names are capitalised and must already exist on the project.
        channel.set(providers.gradleProperty("hangarChannel").orElse("Release"))
        apiKey.set(providers.environmentVariable("HANGAR_API_TOKEN"))
        // Written by the release workflow before it publishes, from CHANGELOG.md. Absent
        // only on a local invocation - a manual publish, or a manual re-run of a failed
        // one - where the fallback applies. That can be a stable release as easily as a
        // pre-release, so the text is neutral about which; it points at the changelog,
        // never at the GitHub release, which is not a changelog either.
        changelog.set(
            providers.fileContents(layout.buildDirectory.file("release-notes.md")).asText
                .orElse("See CHANGELOG.md for this version.")
        )

        platforms {
            paper {
                jar.set(tasks.shadowJar.flatMap { it.archiveFile })
                platformVersions.set(
                    providers.gradleProperty("hangarPlatformVersions")
                        .orElse(releaseGameVersions)
                        .map { versions -> versions.split(",").map(String::trim).filter(String::isNotEmpty) }
                )
            }
        }

        // The Hangar resource page, synced by syncPluginPublicationMainResourcePagePageToHangar
        // (PATCH pages/edit/<id>, which needs the edit_page permission on the API key). The
        // text is docs/store-description.md without its generated comment and the blank
        // lines around it: exactly what was pasted by hand, and what Hangar stores and
        // returns byte for byte. Read only when the sync task runs.
        pages {
            resourcePage(
                providers.fileContents(layout.projectDirectory.file("docs/store-description.md")).asText
                    .map { text ->
                        check(text.startsWith("<!--") && "-->" in text) {
                            "docs/store-description.md must start with its generated comment; " +
                                "run python scripts/store-description.py"
                        }
                        text.substringAfter("-->").trim()
                    }
            )
        }
    }
}

// The page sync cannot fail on its own: hangar-publish-plugin 0.1.4 logs a rejected edit
// (a 403 for a key without edit_page, a 404 for a wrong slug) and lets the task succeed.
// So the task reads the page back from the public endpoint and fails unless Hangar now
// holds exactly the content it sent. A rejected edit of an already identical page still
// passes here; its "Error using endpoint" line in the log is the only sign of it.
tasks.withType<PageSyncTask>().configureEach {
    doLast {
        val expected = page.get().content.get()
        val url = apiEndpoint.get() + "pages/main/" + id.get()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)
        )
        if (response.statusCode() != 200 || response.body() != expected) {
            throw GradleException(
                "Hangar resource page for '${id.get()}' does not match docs/store-description.md " +
                    "after the sync (GET $url returned ${response.statusCode()}). Check the log " +
                    "above for the rejected edit; the API key needs the edit_page permission."
            )
        }
        logger.lifecycle("Hangar resource page for '${id.get()}' matches docs/store-description.md.")
    }
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
        // silently. It is satisfied: 420 tests are discovered and run. Read the counts, not
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

        // Licence notices. Every shaded component is MIT, and MIT's one condition is that
        // its copyright and permission notice travel with every copy - which a shaded jar
        // is. None of the upstream jars carries a LICENSE entry of its own, so before this
        // the plugin jar shipped their code with no notice at all. The project's own
        // GPL-3.0 text travels beside them.
        //
        // META-INF, not the jar root: the root is the plugin's resource namespace, where a
        // LICENSE would sit next to plugin.yml and config.yml and could be mistaken for, or
        // shadowed by, a resource a server loads.
        //
        // The INCLUDE line above is why the doLast below counts these entries rather than
        // merely looking for them: under INCLUDE a dependency that one day ships its own
        // META-INF/LICENSE would be written as a SECOND entry of the same name, and which
        // one a reader of the jar gets is then up to the reader.
        from(layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
        }
        from(layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
            into("META-INF")
        }

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
        //   names. Resolution pulls 18 net.kyori modules (plus adventure-bom,
        //   a constraint with no classes; THIRD_PARTY_NOTICES.md lists all 18). Relocating only
        //   adventure-platform-bukkit and adventure-text-minimessage would leave
        //   adventure-api, adventure-key, adventure-nbt, the serializers,
        //   adventure-platform-{api,facet} and net.kyori.examination sitting unrelocated at
        //   net.kyori, alongside the copy Paper ships natively - which is the exact
        //   collision the relocation exists to prevent.
        //
        //   The consequence is the constraint notify/Notifier enforces: our Component is
        //   NOT Paper's Component, so player.sendMessage(Component) is a runtime landmine on
        //   Paper. Every output call goes through Notifier, and Notifier through
        //   BukkitAudiences, on all four platforms.
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
                // Notifier#legacy's serializer. It is only ever a transitive dependency, so this
                // is the one build-time signal that a trimming pass dropped it; the boot legs are
                // the second.
                requireEntry("com/ninja6/sessionpulse/lib/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer.class")

                // The licence notices, exactly once each. Not requireEntry: these are not
                // relocated, and "missing" is only half of what can go wrong - see the
                // duplicatesStrategy note on the from() blocks above. Counted over the raw
                // entry list, which keeps a duplicated name as two entries.
                fun requireSingleNotice(entry: String) {
                    val count = names.count { it == entry }
                    if (count != 1) {
                        throw GradleException(
                            "Licence notice $entry occurs $count time(s) in the jar; it must " +
                                "occur exactly once. Zero means the shaded code ships without " +
                                "the notice its MIT licence requires; more than one means a " +
                                "dependency brought its own copy under DuplicatesStrategy.INCLUDE."
                        )
                    }
                }
                requireSingleNotice("META-INF/LICENSE")
                requireSingleNotice("META-INF/THIRD_PARTY_NOTICES.md")

                // And byte-for-byte the files in the repository root. Exactly-once proves
                // there is one entry by that name, not that it is ours: a dependency's
                // META-INF/LICENSE written in place of the from() copy would pass the count
                // and ship the wrong notice.
                fun requireSameBytes(entry: String, source: File) {
                    val packaged = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
                    if (!packaged.contentEquals(source.readBytes())) {
                        throw GradleException(
                            "Licence notice $entry in the jar does not match ${source.name} in " +
                                "the repository root byte for byte. Something other than the " +
                                "project's own file was packaged under that name."
                        )
                    }
                }
                requireSameBytes("META-INF/LICENSE", layout.projectDirectory.file("LICENSE").asFile)
                requireSameBytes(
                    "META-INF/THIRD_PARTY_NOTICES.md",
                    layout.projectDirectory.file("THIRD_PARTY_NOTICES.md").asFile
                )

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
            logger.lifecycle("Relocation and licence notices verified in ${jar.name}.")
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

    // Packages the protocol bot as a runnable fat jar at a fixed, unversioned path the smoke
    // legs refer to by name. Deliberately NOT wired into build, check or test: the required
    // Build and Test check must not depend on a SNAPSHOT from a third-party repository, so
    // only the bot legs of the boot job run this task.
    register<Jar>("botClientJar") {
        archiveBaseName.set("SessionPulseProbeBot")
        archiveClassifier.set("")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory.dir("test-fixtures"))
        manifest { attributes["Main-Class"] = "com.ninja6.botclient.SessionPulseProbeBot" }
        from(sourceSets["botClient"].output)
        // Runs as its own process against a live server, so unlike the plugin it does need
        // its dependencies inside it.
        from(configurations["botClientRuntimeClasspath"].map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}
