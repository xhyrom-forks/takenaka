/*
 * This file is part of takenaka, licensed under the Apache License, Version 2.0 (the "License").
 *
 * Copyright (c) 2023-2024 Matous Kucera
 *
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("Main")

package me.kcra.takenaka.generator.web.cli

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import kotlinx.cli.*
import kotlinx.coroutines.runBlocking
import me.kcra.takenaka.core.compositeWorkspace
import me.kcra.takenaka.core.mapping.adapter.*
import me.kcra.takenaka.core.mapping.analysis.impl.AnalysisOptions
import me.kcra.takenaka.core.mapping.analysis.impl.MappingAnalyzerImpl
import me.kcra.takenaka.core.mapping.analysis.impl.StandardProblemKinds
import me.kcra.takenaka.core.mapping.resolve.impl.*
import me.kcra.takenaka.core.util.objectMapper
import me.kcra.takenaka.core.workspace
import me.kcra.takenaka.generator.common.provider.impl.ResolvingMappingProvider
import me.kcra.takenaka.generator.common.provider.impl.SimpleAncestryProvider
import me.kcra.takenaka.generator.common.provider.impl.SimpleMappingProvider
import me.kcra.takenaka.generator.common.provider.impl.buildMappingConfig
import me.kcra.takenaka.generator.web.*
import me.kcra.takenaka.generator.web.transformers.CSSInliningTransformer
import me.kcra.takenaka.generator.web.transformers.MinifyingTransformer
import mu.KotlinLogging
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger {}

/**
 * Minification options.
 */
enum class MinifierImpls {
    /**
     * Deterministic minification.
     */
    DETERMINISTIC,

    /**
     * Normal minification.
     */
    NORMAL,

    /**
     * No minification.
     */
    NONE
}

/**
 * The application entrypoint.
 */
fun main(args: Array<String>) {
    val parser = ArgParser("web-cli")
    val output by parser.option(ArgType.String, shortName = "o", description = "Output directory").default("output")
    val version by parser.option(ArgType.String, shortName = "v", description = "Target Minecraft version, can be specified multiple times").multiple().required()
    val cache by parser.option(ArgType.String, shortName = "c", description = "Caching directory for mappings and other resources").default("cache")
    val server by parser.option(ArgType.Boolean, description = "Include server mappings in the documentation").default(false)
    val client by parser.option(ArgType.Boolean, description = "Include client mappings in the documentation").default(false)
    val strictCache by parser.option(ArgType.Boolean, description = "Enforces strict cache validation").default(false)
    val clean by parser.option(ArgType.Boolean, description = "Removes previous build output and cache before launching").default(false)
    val noJoined by parser.option(ArgType.Boolean, description = "Don't cache joined mapping files").default(false)

    // generator-specific settings below

    val minifier by parser.option(ArgType.Choice<MinifierImpls>(), shortName = "m", description = "The minifier implementation used for minifying the documentation").default(MinifierImpls.NORMAL)
    val javadoc by parser.option(ArgType.String, shortName = "j", description = "Javadoc site that should be referenced in the documentation, can be specified multiple times").multiple()
    val synthetic by parser.option(ArgType.Boolean, shortName = "s", description = "Include synthetic classes and class members in the documentation").default(false)
    val noMeta by parser.option(ArgType.Boolean, description = "Don't emit HTML metadata tags in OpenGraph format").default(false)
    val noPseudoElems by parser.option(ArgType.Boolean, description = "Don't emit pseudo-elements (increases file size)").default(false)

    parser.parse(args)

    if (!server && !client) {
        logger.error { "no mappings were specified, add --server and/or --client" }
        exitProcess(-1)
    }

    val workspace = workspace {
        rootDirectory(output)
    }
    val cacheWorkspace = compositeWorkspace {
        rootDirectory(cache)
    }

    if (clean) {
        workspace.clean()
        cacheWorkspace.clean()
    }

    // generator setup below

    val objectMapper = objectMapper()
    val xmlMapper = XmlMapper()

    val mappingsCache = cacheWorkspace.createCompositeWorkspace {
        name = "mappings"
    }
    val sharedCache = cacheWorkspace.createWorkspace {
        name = "shared"
    }

    val yarnProvider = YarnMetadataProvider(sharedCache, xmlMapper, relaxedCache = !strictCache)
    val mappingConfig = buildMappingConfig {
        version(version)
        workspace(mappingsCache)

        // remove Searge's ID namespace, it's not necessary
        intercept { v ->
            NamespaceFilter(v, "searge_id")
        }
        // remove static initializers, not needed in the documentation
        intercept(::StaticInitializerFilter)
        // remove overrides of java/lang/Object, they are implicit
        intercept(::ObjectOverrideFilter)
        // remove obfuscated method parameter names, they are a filler from Searge
        intercept(::MethodArgSourceFilter)

        contributors { versionWorkspace ->
            val mojangProvider = MojangManifestAttributeProvider(versionWorkspace, objectMapper, relaxedCache = !strictCache)
            val spigotProvider = SpigotManifestProvider(versionWorkspace, objectMapper, relaxedCache = !strictCache)

            buildList {
                if (server) {
                    add(VanillaServerMappingContributor(versionWorkspace, mojangProvider, relaxedCache = !strictCache))
                    add(MojangServerMappingResolver(versionWorkspace, mojangProvider))
                }
                if (client) {
                    add(VanillaClientMappingContributor(versionWorkspace, mojangProvider, relaxedCache = !strictCache))
                    add(MojangClientMappingResolver(versionWorkspace, mojangProvider))
                }

                add(IntermediaryMappingResolver(versionWorkspace, sharedCache))
                add(YarnMappingResolver(versionWorkspace, yarnProvider, relaxedCache = !strictCache))
                add(SeargeMappingResolver(versionWorkspace, sharedCache, relaxedCache = !strictCache))

                // Spigot resolvers have to be last
                if (server) {
                    val link = LegacySpigotMappingPrepender.Link()

                    add(
                        // 1.16.5 mappings have been republished with proper packages, even though the reobfuscated JAR does not have those
                        // See: https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/commits/80d35549ec67b87a0cdf0d897abbe826ba34ac27
                        link.createPrependingContributor(
                            SpigotClassMappingResolver(versionWorkspace, xmlMapper, spigotProvider, relaxedCache = !strictCache),
                            prependEverything = versionWorkspace.version.id == "1.16.5"
                        )
                    )
                    add(link.createPrependingContributor(SpigotMemberMappingResolver(versionWorkspace, xmlMapper, spigotProvider, relaxedCache = !strictCache)))
                }
            }
        }

        joinedOutputPath { workspace ->
            if (noJoined) return@joinedOutputPath null
            val fileName = when {
                client && server -> "client+server.tiny"
                client -> "client.tiny"
                else -> "server.tiny"
            }

            workspace[fileName]
        }
    }

    val mappingProvider = ResolvingMappingProvider(mappingConfig, objectMapper, xmlMapper)
    val analyzer = MappingAnalyzerImpl(
        AnalysisOptions(
            innerClassNameCompletionCandidates = setOf("spigot"),
            inheritanceAdditionalNamespaces = setOf("searge") // mojang could be here too for maximal parity, but that's in exchange for a little bit of performance
        )
    )

    val webConfig = buildWebConfig {
        val chosenMappings = when {
            client && server -> "client- and server-side"
            client -> "client-side"
            else -> "server-side"
        }

        welcomeMessage(
            """
                <h1>Welcome to the browser for Minecraft: Java Edition $chosenMappings mappings!</h1>
                <br/>
                <p>
                    You can move through this site by following links to specific versions/packages/classes/...
                    or use the nifty search field in the top right corner (appears when in a versioned page!).
                </p>
                <br/>
                <p>
                    It is possible that there are errors in mappings displayed here, but we've tried to make them as close as possible to the runtime naming.<br/>
                    If you run into such an error, please report it at <a href="https://github.com/zlataovce/takenaka/issues/new">the issue tracker</a>!
                </p>
            """.trimIndent()
        )
        if (!synthetic) {
            welcomeMessage("$welcomeMessage<br/><strong>NOTE: This build of the site excludes synthetic members (generated by the compiler, i.e. not in the source code).</strong>")
        }

        emitMetaTags(!noMeta)
        emitPseudoElements(!noPseudoElems)

        transformer(CSSInliningTransformer("cdn.jsdelivr.net"))
        logger.info { "using minification mode $minifier" }
        if (minifier != MinifierImpls.NONE) {
            transformer(MinifyingTransformer(isDeterministic = minifier == MinifierImpls.DETERMINISTIC))
        }

        val indexers = mutableListOf<ClassSearchIndex>(objectMapper.modularClassSearchIndexOf(JDK_17_BASE_URL))
        javadoc.mapTo(indexers) { javadocDef ->
            val javadocParams = javadocDef.split('+', limit = 2)

            when (javadocParams.size) {
                2 -> classSearchIndexOf(javadocParams[1], javadocParams[0])
                else -> objectMapper.modularClassSearchIndexOf(javadocParams[0])
            }
        }

        logger.info { "using ${indexers.size} javadoc indexer(s)" }

        index(indexers)

        replaceCraftBukkitVersions("spigot")
        friendlyNamespaces("mojang", "spigot", "yarn", "searge", "intermediary", "source")
        namespace("mojang", "Mojang", "#4D7C0F", AbstractMojangMappingResolver.META_LICENSE)
        namespace("spigot", "Spigot", "#CA8A04", AbstractSpigotMappingResolver.META_LICENSE)
        namespace("yarn", "Yarn", "#626262", YarnMappingResolver.META_LICENSE)
        namespace("searge", "Searge", "#B91C1C", SeargeMappingResolver.META_LICENSE)
        namespace("intermediary", "Intermediary", "#0369A1", IntermediaryMappingResolver.META_LICENSE)
        namespace("source", "Obfuscated", "#581C87")
    }

    val generator = WebGenerator(workspace, webConfig)

    logger.info { "starting generator" }
    val time = measureTimeMillis {
        runBlocking {
            val mappings = mappingProvider.get(analyzer)
            analyzer.problemKinds.forEach { kind ->
                if (synthetic && kind == StandardProblemKinds.SYNTHETIC) return@forEach

                analyzer.acceptResolutions(kind)
            }

            generator.generate(
                SimpleMappingProvider(mappings),
                SimpleAncestryProvider(null, listOf("mojang", "spigot", "searge", "intermediary"))
            )
        }
    }
    logger.info { "generator finished in ${time / 1000} second(s)" }
}
