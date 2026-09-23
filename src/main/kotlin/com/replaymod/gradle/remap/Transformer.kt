package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.legacy.LegacyMapping
import org.cadixdev.lorenz.MappingSet
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.system.exitProcess

// fallen's fork: optimize use physical source roots for PSI
/**
 * The class a `@Mixin` names, taken as a bare identifier. Only the simple name is needed: a class defined in
 * this same source set is referred to by its simple name, and anything else resolves through the classpath,
 * which the cache key already covers.
 */
private val MIXIN_TARGET = Regex("""@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([A-Za-z_]\w*)""")

data class PhysicalSourceFile(
    val file: File,
    val sourceRoot: File,
    val sourceText: String,
)

class Transformer(private val map: MappingSet) {
    var classpath: Array<String>? = null
    var remappedClasspath: Array<String>? = null
    var jdkHome: File? = null
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false
    var enableMessageCollector = true
    var verboseCompilerMessages = false

    /**
     * Optional per-file result cache. Supplied by the caller so it can live in the consumer's build directory.
     * The PSI environment still has to be built over every source file, but each file's rewrite is independent,
     * so a file whose text and mapping are unchanged can reuse its previous output instead of being remapped
     * again - which is where the bulk of the time goes when only a handful of files changed.
     */
    var remappedFileCacheDir: File? = null

    /** Fingerprint of the mapping in use. Entries from different mappings must never be mixed. */
    var remappedFileCacheKey: String? = null

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> =
            remap(sources, emptyMap())

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>, processedSources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        // fallen's fork: optimize use physical source roots for PSI - extract common impl
        return remapInternal(sources, { processedSources }, null)
    }

    /**
     * Same as [remap], except that the preprocessed sources are produced on demand.
     *
     * They are only consulted once a rewrite actually runs, and a full cache hit never reaches that point, so a
     * caller that would otherwise have to compute them up front can hand the work over instead.
     */
    @Throws(IOException::class)
    fun remapOnDemand(sources: Map<String, String>, processedSources: () -> Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        return remapInternal(sources, processedSources, null)
    }

    // fallen's fork: optimize use physical source roots for PSI - add PhysicalSourceFile variant
    @Throws(IOException::class)
    fun remapFromFiles(sources: Map<String, PhysicalSourceFile>, processedSources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        return remapInternal(sources.mapValues { it.value.sourceText }, { processedSources }, sources)
    }

    // fallen's fork: optimize use physical source roots for PSI - extract common impl
    private fun remapInternal(sources: Map<String, String>, processedSourcesProvider: () -> Map<String, String>, physicalSourceFiles: Map<String, PhysicalSourceFile>?): Map<String, Pair<String, List<Pair<Int, String>>>> {
        // The preprocessed sources are pure string work over the sources, and nothing on the replay path needs
        // them, so they stay unevaluated until one of the few places below actually asks.
        val processedSources by lazy(processedSourcesProvider)
        // What the remapper parses as its PSI basis is the preprocessed text, not the raw sources: it reads
        // annotation arguments out of whatever it is handed, and a directive may already have swapped in the
        // alternative this version needs. Files the preprocessing left alone fall back to their raw text.
        val psiSources by lazy { sources.mapValues { (name, text) -> processedSources[name] ?: text } }
        // If every source is already cached then there is nothing to analyse, and the PSI environment - by far
        // the most expensive part of this method - does not need to be built at all. The cache key covers the
        // entire source set and the settings, so a full hit means the previous result for exactly this input is
        // still valid; the per-file loop below would only read the same files back.
        val cacheDir = remappedFileCacheDir
        val cacheKey = remappedFileCacheKey
        // The only thing a file's rewrite takes from *other* sources is the class it names in `@Mixin`: when
        // that class lives in a dependency, its structure is already covered by the classpath part of the key,
        // and when it is defined here it is a real cross-file input. Fold in exactly those targets, so a file
        // whose targets did not move keeps its previous result while the ones that did get rewritten. Pattern
        // annotations and Kotlin scope are whole-set inputs by nature, so when either is in play the whole set
        // is folded into every file's key.
        val sourceClassByName = HashMap<String, String>()
        for (path in sources.keys) {
            val simple = path.substringAfterLast('/').substringBeforeLast('.')
            if (simple.isNotEmpty()) sourceClassByName.putIfAbsent(simple, path)
        }
        val setWideDependencies = HashSet<String>()
        patternAnnotation?.let { annotation ->
            val annotationName = annotation.substringAfterLast('.')
            sources.keys.filterTo(setWideDependencies) { sources.getValue(it).contains(annotationName) }
        }
        sources.keys.filterTo(setWideDependencies) { it.endsWith(".kt") || it.endsWith(".kts") }
        val cacheInputs = HashMap<String, String>()
        if (cacheDir != null && cacheKey != null) {
            for ((name, text) in sources) {
                val input = StringBuilder(text)
                if ('@' in text) {
                    MIXIN_TARGET.findAll(text).mapNotNull { sourceClassByName[it.groupValues[1]] }
                        .filter { it != name }
                        .distinct()
                        .forEach { input.append('\u0000').append(it).append('\u0000').append(sources.getValue(it)) }
                }
                setWideDependencies.forEach { dependency ->
                    if (dependency != name) {
                        input.append('\u0000').append(dependency).append('\u0000').append(sources.getValue(dependency))
                    }
                }
                cacheInputs[name] = input.toString()
            }
            if (cacheInputs.all { (name, input) -> cacheFileFor(cacheDir, cacheKey, name, input).isFile }) {
                return cacheInputs.mapValues { (name, input) ->
                    cacheFileFor(cacheDir, cacheKey, name, input).readText() to emptyList<Pair<Int, String>>()
                }
            }
        }
        val tmpDir = if (physicalSourceFiles == null) Files.createTempDirectory("remap") else null
        val processedTmpDir = if (manageImports) Files.createTempDirectory("remap-processed") else null  // fallen's fork: optimize skip unused processed temp root
        val disposable = Disposer.newDisposable()
        try {
            if (physicalSourceFiles == null) {  // fallen's fork: optimize use physical source roots for PSI - warp with if
                // Directories repeat across the source set, so create each once up front; the per-file syscall
                // sequence is what dominates this stage on every version node. The files themselves are
                // independent, so write them in parallel.
                psiSources.keys.mapNotNull { tmpDir!!.resolve(it).parent }.toHashSet().forEach { Files.createDirectories(it) }
                val processedRoot = processedTmpDir
                if (processedRoot != null) {
                    psiSources.keys.mapNotNull { processedRoot.resolve(it).parent }.toHashSet().forEach { Files.createDirectories(it) }
                }
                psiSources.entries.parallelStream().forEach { (unitName, source) ->
                    Files.write(tmpDir!!.resolve(unitName), source.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE)
                    // fallen's fork: optimize skip unused processed temp root
                    processedRoot?.let { it2 ->
                        val processedSource = processedSources[unitName] ?: source
                        Files.write(it2.resolve(unitName), processedSource.toByteArray(), StandardOpenOption.CREATE)
                    }
                }
            } else {
                // fallen's fork: optimize skip unused processed temp root - begin
                processedTmpDir?.let { processedRoot ->
                    for ((unitName, source) in sources) {
                        val processedSource = processedSources[unitName] ?: source
                        val processedPath = processedRoot.resolve(unitName)
                        Files.createDirectories(processedPath.parent)
                        Files.write(processedPath, processedSource.toByteArray(), StandardOpenOption.CREATE)
                    }
                }
                // fallen's fork: optimize skip unused processed temp root - end
            }

            val config = CompilerConfiguration()
            config.put(CommonConfigurationKeys.MODULE_NAME, "main")
            jdkHome?.let {config.setupJdk(it) }

            // fallen's fork: optimize use physical source roots for PSI - begin
            val sourceRoots = if (physicalSourceFiles == null) {
                listOf(tmpDir!!.toFile())
            } else {
                physicalSourceFiles.values.map { it.sourceRoot.absoluteFile }.distinctBy { it.toPath().normalize() }
            }
            sourceRoots.forEach { sourceRoot ->  // fallen's fork: optimize use physical source roots for PSI - warp with sourceRoots.forEach
                config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(sourceRoot, ""))
                val kotlinSourceRoot = try {
                    kotlinSourceRoot1521(sourceRoot.absolutePath, false)
                } catch (e: NoSuchMethodError) {
                    kotlinSourceRoot190(sourceRoot.absolutePath, false)
                }
                config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, kotlinSourceRoot)
            }
            // fallen's fork: optimize use physical source roots for PSI - end

            config.addAll<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, classpath!!.map { JvmClasspathRoot(File(it)) })
            config.put<MessageCollector>(
                CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                if (enableMessageCollector) PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
                else MessageCollector.NONE
            )

            // Our PsiMapper only works with the PSI tree elements, not with the faster (but kotlin-specific) classes
            config.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

            // Mark Registry as loaded, otherwise RegistryKey will (provided a sufficiently complex project) log
            // messages about it being accessed before it is loaded (and it won't ever be loaded naturally).
            val loadedField = try {
                Registry::class.java.getDeclaredField("myLoaded")
            } catch (_: NoSuchFieldException) {
                Registry::class.java.getDeclaredField("isLoaded")
            }
            loadedField.isAccessible = true
            loadedField.set(Registry.getInstance(), true)

            val environment = KotlinCoreEnvironment.createForProduction(
                    disposable,
                    config,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            @Suppress("DEPRECATION")
            val rootArea = Extensions.getRootArea()
            synchronized(rootArea) {
                if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                    rootArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE)
                }
            }

            val project = environment.project as MockProject
            val psiManager = PsiManager.getInstance(project)
            val vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem

            // fallen's fork: optimize use physical source roots for PSI - begin
            fun findSourceFile(name: String) = if (physicalSourceFiles == null) {
                vfs.findFileByIoFile(tmpDir!!.resolve(name).toFile())!!
            } else {
                vfs.findFileByIoFile(physicalSourceFiles.getValue(name).file)!!
            }
            // fallen's fork: optimize use physical source roots for PSI - end

            // The per-file loop below resolves its own PSI tree lazily, and skips that entirely on a cache hit,
            // so the only consumer of a fully materialised PSI map is the Kotlin list handed to the analysis.
            // Resolve just the Kotlin sources: a Java-only source set stops paying for a PSI tree of every
            // source on every version node.
            val ktFiles = sources.keys
                .filter { it.endsWith(".kt") || it.endsWith(".kts") }
                .mapNotNull { psiManager.findFile(findSourceFile(it)) as? KtFile }

            val analysis = try {
                analyze1521(environment, ktFiles)
            } catch (e: NoSuchMethodError) {
                try {
                    analyze1620(environment, ktFiles)
                } catch (e: NoSuchMethodError) {
                    analyze200(environment, ktFiles)
                }
            }

            val remappedEnv = remappedClasspath?.let {
                setupRemappedProject(disposable, it, processedTmpDir)
            }

            val patterns = patternAnnotation?.let { annotationFQN ->
                val patterns = PsiPatterns(annotationFQN)
                val annotationName = annotationFQN.substring(annotationFQN.lastIndexOf('.') + 1)
                for ((unitName, source) in sources) {
                    if (!source.contains(annotationName)) continue
                    try {
                        val patternFile = findSourceFile(unitName)
                        val patternPsiFile = psiManager.findFile(patternFile)!!
                        patterns.read(patternPsiFile, processedSources[unitName]!!)
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to read patterns from file \"$unitName\".", e)
                    }
                }
                patterns
            }

            val autoImports = if (manageImports && remappedEnv != null) {
                AutoImports(remappedEnv)
            } else {
                null
            }

            // One cache for the whole run: the target-side lookup behind `resolvesOnTarget` is a global index
            // query and the same (owner, method) pair recurs across files, so per-file state would redo it.
            val resolvesCache = HashMap<Pair<String, String>, Boolean>()
            val results = HashMap<String, Pair<String, List<Pair<Int, String>>>>()
            for (name in psiSources.keys) {
                val cacheFile = if (cacheDir != null && cacheKey != null) {
                    cacheFileFor(cacheDir, cacheKey, name, cacheInputs.getValue(name))
                } else {
                    null
                }
                if (cacheFile != null && cacheFile.isFile) {
                    results[name] = cacheFile.readText() to emptyList()
                    continue
                }

                val file = findSourceFile(name)
                val psiFile = psiManager.findFile(file)!!

                var (text, errors) = try {
                    PsiMapper(map, remappedEnv?.project, psiFile, analysis.bindingContext, patterns, resolvesCache).remapFile()
                } catch (e: Exception) {
                    throw RuntimeException("Failed to map file \"$name\".", e)
                }

                if (autoImports != null && "/* remap: no-manage-imports */" !in text) {
                    val processedText = processedSources[name] ?: text
                    text = autoImports.apply(psiFile, text, processedText)
                }

                results[name] = text to errors
                // Only a clean result is cached: errors carry line numbers the caller reports, and reusing a
                // cached text without them would silently drop diagnostics.
                if (cacheFile != null && errors.isEmpty()) {
                    runCatching {
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(text)
                    }
                }
            }
            return results
        } finally {
            // fallen's fork: optimize use physical source roots for PSI - begin
            tmpDir?.let { root ->
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
            // fallen's fork: optimize use physical source roots for PSI - end

            // fallen's fork: optimize skip unused processed temp root - begin
            processedTmpDir?.let { processedRoot ->
                Files.walk(processedRoot).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
            // fallen's fork: optimize skip unused processed temp root - end
            Disposer.dispose(disposable)
        }
    }

    /**
     * Cache path for one file's rewritten text. The key covers the mapping, the file's logical path and its
     * input text, so a change to any of them lands on a different entry.
     */
    private fun cacheFileFor(dir: File, mappingKey: String, name: String, sourceText: String): File {
        val digest = java.security.MessageDigest.getInstance("MD5")
        digest.update(mappingKey.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(sourceText.toByteArray(Charsets.UTF_8))
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return File(File(dir, hex.substring(0, 2)), "$hex.txt")
    }

    private fun CompilerConfiguration.setupJdk(jdkHome: File) {
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
            val roots = PathUtil.getJdkClassesRoots(jdkHome).map { JvmClasspathRoot(it, true) }
            addAll(CLIConfigurationKeys.CONTENT_ROOTS, 0, roots)
        }
    }

    private fun setupRemappedProject(disposable: Disposable, classpath: Array<String>, sourceRoot: Path?): KotlinCoreEnvironment { // fallen's fork: optimize skip unused processed temp root
        val config = CompilerConfiguration()
        (remappedJdkHome ?: jdkHome)?.let { config.setupJdk(it) }
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(File(it)) })
        // fallen's fork: optimize skip unused processed temp root - begin
        if (manageImports) {
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(requireNotNull(sourceRoot).toFile(), ""))
        }
        // fallen's fork: optimize skip unused processed temp root - end
        config.put(
            CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            if (enableMessageCollector) PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
            else MessageCollector.NONE
        )

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        try {
            analyze1521(environment, emptyList())
        } catch (e: NoSuchMethodError) {
            try {
                analyze1620(environment, emptyList())
            } catch (e: NoSuchMethodError) {
                analyze200(environment, emptyList())
            }
        }
        return environment
    }

    companion object {

        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val mappings: MappingSet = if (args[0].isEmpty()) {
                MappingSet.create()
            } else {
                LegacyMapping.readMappingSet(File(args[0]).toPath(), args[1] == "true")
            }
            val transformer = Transformer(mappings)

            val reader = BufferedReader(InputStreamReader(System.`in`))

            transformer.classpath = (1..Integer.parseInt(args[2])).map { reader.readLine() }.toTypedArray()

            val sources = mutableMapOf<String, String>()
            while (true) {
                val name = reader.readLine()
                if (name == null || name.isEmpty()) {
                    break
                }

                val lines = arrayOfNulls<String>(Integer.parseInt(reader.readLine()))
                for (i in lines.indices) {
                    lines[i] = reader.readLine()
                }
                val source = lines.joinToString("\n")

                sources[name] = source
            }

            val results = transformer.remap(sources)

            for (name in sources.keys) {
                println(name)
                val lines = results.getValue(name).first.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
                println(lines.size)
                for (line in lines) {
                    println(line)
                }
            }

            if (results.any { it.value.second.isNotEmpty() }) {
                exitProcess(1)
            }
        }

        init {
            // Fix "WARN: Failed to initialize native filesystem for Windows" warnings
            setIdeaIoUseFallback()

            // Mute intellij platform logger for those "WARN: The registry key 'xxx' accessed, but not loaded yet" warnings
            org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger.setFactory {
                org.jetbrains.kotlin.utils.PrintingLogger(PrintStream(object : OutputStream() {
                    override fun write(b: Int) {}
                }))
            }
        }
    }

}
