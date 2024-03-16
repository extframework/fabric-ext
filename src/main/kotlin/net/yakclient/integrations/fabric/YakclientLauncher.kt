package net.yakclient.integrations.fabric

import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.fabricmc.loader.impl.FormattedException
import net.fabricmc.loader.impl.game.GameProvider
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.launch.FabricMixinBootstrap
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot
import net.fabricmc.loader.impl.util.LoaderUtil
import net.fabricmc.loader.impl.util.log.Log
import net.fabricmc.loader.impl.util.log.LogCategory
import net.yakclient.archives.Archives
import net.yakclient.boot.loader.*
import net.yakclient.common.util.readInputStream
import net.yakclient.common.util.toBytes
import net.yakclient.components.extloader.api.environment.extract
import net.yakclient.components.extloader.api.target.ApplicationTarget
import net.yakclient.components.extloader.target.TargetLinker
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.ProtectionDomain
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Manifest
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.io.path.toPath


// TODO document this, im not going to comment on it right now since it also needs alot of cleaning up
class YakclientLauncher(
    private var envType: EnvType
) : FabricLauncherBase() {
    private var development: Boolean = false

    private var properties: Map<String, Any> = HashMap()
    private val classloader = KnotClassLoaderReplacement() //Any // KnotClassloaderInterface

    private class KnotClassLoaderReplacement : MutableClassLoader(
        name = "Knot Class loader replacement",
        classes = MutableClassProvider(mutableListOf(FabricIntegrationTweaker.tweakerEnv[TargetLinker].extract().targetTarget.relationship.classes)),
        resources = MutableResourceProvider(mutableListOf(FabricIntegrationTweaker.tweakerEnv[TargetLinker].extract().targetTarget.relationship.resources)),
        parent = FabricIntegrationTweaker.fabricClassloader
    ) {
        private val classCache: MutableMap<String, Class<*>> = ConcurrentHashMap()

        fun isClassLoaded(name: String): Boolean {
            return findLoadedClass(name) != null
        }

        override fun loadClass(name: String): Class<*> = classCache[name] ?: synchronized(getClassLoadingLock(name)) {
            val findLoadedClass = findLoadedClass(name)
            val c = findLoadedClass
                ?: classProvider.findClass(name)
                ?: runCatching { parent.loadClass(name) }.getOrNull()
                ?: tryDefine(name)
                ?: throw ClassNotFoundException(name)

            classCache[name] = c

            c
        }

        override fun tryDefine(name: String): Class<*>? {
            val transformer = MixinServiceKnot::class.java.getDeclaredMethod(
                "getTransformer"
            ).apply { trySetAccessible() }.invoke(null) as IMixinTransformer

            val source = sourceProvider.findSource(name)
            val cls = source?.toBytes()?.let {
                transformer.transformClassBytes(name, name, it)
            }?.let(ByteBuffer::wrap)?.let {
                classCache[name] ?: defineClass(name, it, ProtectionDomain(null, null))
            }

            if (cls != null)
                classCache[name] = cls

            return cls
        }

        companion object {
            init {
                registerAsParallelCapable()
            }
        }
    }

    private val classPath: MutableList<Path> = ArrayList()
    internal lateinit var provider: YakclientGameProvider
    private var unlocked = false

    fun init(args: Array<String>): FabricLoader {
        setProperties(properties)

        // configure fabric vars
        classPath.clear()
        var missing: MutableList<String?>? = null
        var unsupported: MutableList<String?>? = null
        for (cpEntry in System.getProperty("java.class.path").split(File.pathSeparator.toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()) {
            if (cpEntry == "*" || cpEntry.endsWith(File.separator + "*")) {
                if (unsupported == null) unsupported = ArrayList()
                unsupported.add(cpEntry)
                continue
            }
            val path = Paths.get(cpEntry)
            if (!Files.exists(path)) {
                if (missing == null) missing = ArrayList()
                missing.add(cpEntry)
                continue
            }
            classPath.add(LoaderUtil.normalizeExistingPath(path))
        }
        if (unsupported != null) Log.warn(
            LogCategory.KNOT,
            "Knot does not support wildcard class path entries: %s - the game may not load properly!",
            java.lang.String.join(", ", unsupported)
        )
        if (missing != null) Log.warn(
            LogCategory.KNOT,
            "Class path entries reference missing files: %s - the game may not load properly!",
            java.lang.String.join(", ", missing)
        )
        provider = createGameProvider(args)

        Log.finishBuiltinConfig()
        Log.info(
            LogCategory.GAME_PROVIDER,
            "Loading %s %s with Yakclient/Fabric Loader %s",
            provider.gameName,
            provider.rawGameVersion,
            FabricLoaderImpl.VERSION
        )

        // Setup classloader
        // TODO: Provide KnotCompatibilityClassLoader in non-exclusive-Fabric pre-1.13 environments?
        val useCompatibility = provider.requiresUrlClassLoader() || System.getProperty(
            "fabric.loader.useCompatibilityClassLoader",
            "false"
        ).toBoolean()

//        classloader = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClassLoaderInterface").getDeclaredMethod(
//            "create", Boolean::class.java, Boolean::class.java, EnvType::class.java, GameProvider::class.java
//        ).apply { trySetAccessible() }.invoke(
//            null,
//            useCompatibility,
//            isDevelopment,
//            envType,
//            provider
//        ) //KnotClassLoaderInterface.create(useCompatibility, isDevelopment, envType, provider)
//        val cl = classloader::class.java.getDeclaredMethod("getClassLoader").apply { trySetAccessible() }
//            .invoke(classloader) as ClassLoader

        provider.initialize(this)

        Thread.currentThread().setContextClassLoader(classloader)

        val loader = FabricLoaderImpl.INSTANCE
        loader.setGameProvider(provider)
        development = true
        loader.load()
        development = false
        loader.freeze()

        FabricLoaderImpl.INSTANCE.loadAccessWideners()

        development = true
        FabricMixinBootstrap.init(environmentType, loader)
        development = false
        finishMixinBootstrapping()

//        classloader::class.java.getDeclaredMethod("initializeTransformers").apply { trySetAccessible() }
//            .invoke(classloader) // .initializeTransformers()

        provider.unlockClassPath(this)
        unlocked = true
        try {
            loader.invokeEntrypoints(
                "preLaunch",
                PreLaunchEntrypoint::class.java
            ) { obj: PreLaunchEntrypoint -> obj.onPreLaunch() }
        } catch (e: RuntimeException) {
            throw FormattedException.ofLocalized("exception.initializerFailure", e)
        }
        return loader
    }

    private fun createGameProvider(args: Array<String>): YakclientGameProvider {
        val provider = YakclientGameProvider()

        provider.locateGame(this, args)
        return provider
    }

    override fun getTargetNamespace(): String {
        // TODO: Won't work outside of Yarn
        return "named" // hacky
//        return if (IS_DEVELOPMENT) "named" else "intermediary"
    }

    override fun getClassPath(): List<Path> {
        val reference = FabricIntegrationTweaker.tweakerEnv.get(ApplicationTarget).extract().reference
        return listOf(reference.reference.location.toPath())
//        return listOf()
//        return classPath
    }

    override fun addToClassPath(path: Path, vararg allowedPrefixes: String) {
        Log.debug(LogCategory.KNOT, "Adding $path to classpath.")

        setAllowedPrefixes(path, *allowedPrefixes)

        val archive = Archives.find(path, Archives.Finders.ZIP_FINDER)
        classloader.addSources(ArchiveSourceProvider(archive))
        classloader.addResources(ArchiveResourceProvider(archive))

//        classloader::class.java.getDeclaredMethod("addCodeSource", Path::class.java).apply {
//            trySetAccessible()
//        }.invoke(classloader, path)
    }

    override fun setAllowedPrefixes(path: Path, vararg prefixes: String) {
//        classloader::class.java.getDeclaredMethod("setAllowedPrefixes", Path::class.java, Array<String>::class.java)
//            .apply {
//                trySetAccessible()
//            }.invoke(classloader, path, prefixes.toList().toTypedArray())
    }

    override fun setValidParentClassPath(paths: Collection<Path>) {
//        classloader::class.java.getDeclaredMethod("setValidParentClassPath", Collection::class.java).apply {
//            trySetAccessible()
//        }.invoke(classloader, paths)
    }

    override fun getEnvironmentType(): EnvType {
        return envType
    }

    override fun isClassLoaded(name: String): Boolean {
        return classloader.isClassLoaded(name)
//        classloader
//        return classloader::class.java.getDeclaredMethod("isClassLoaded", String::class.java).apply {
//            trySetAccessible()
//        }.invoke(classloader, name) as Boolean
    }

    @Throws(ClassNotFoundException::class)
    override fun loadIntoTarget(name: String): Class<*> {
        return classloader.loadClass(name)
//        return classloader::class.java.getDeclaredMethod(
//            "loadIntoTarget", String::class.java
//        ).apply { trySetAccessible() }.invoke(classloader, name) as Class<*>
    }

    override fun getResourceAsStream(name: String): InputStream? {
        return classloader.getResourceAsStream(name)
//        return targetClassLoader::class.java.getDeclaredMethod("getResourceAsStream", String::class.java).apply {
//            trySetAccessible()
//        }.invoke(targetClassLoader, name) as? InputStream
    }

    override fun getTargetClassLoader(): ClassLoader {
        return classloader
//        return classloader::class.java.getDeclaredMethod("getClassLoader").apply {
//            trySetAccessible()
//        }.invoke(classloader) as ClassLoader
    }

    @Throws(IOException::class)
    override fun getClassByteArray(name: String, runTransformers: Boolean): ByteArray? {
        // Can ignore transformation as our game provider doesnt provide that.
        val targetRef = FabricIntegrationTweaker.tweakerEnv[ApplicationTarget].extract().reference
        val references = DelegatingSourceProvider((targetRef.dependencyReferences + targetRef.reference).map {
            ArchiveSourceProvider(it)
        })

        return references.findSource(name.replace('/', '.'))?.toBytes() ?: classloader.getResourceAsStream(
            name.replace(
                '.',
                '/'
            ) + ".class"
        )
            ?.readInputStream()

//            ?: classloader::class.java.getDeclaredMethod(
//            "getRawClassBytes",
//            String::class.java
//        ).apply {
//            trySetAccessible()
//        }.invoke(classloader, name) as? ByteArray
    }

    override fun getManifest(originPath: Path): Manifest {
        TODO()
//        Manifest()
//        return classloader::class.java.getDeclaredMethod("getManifest", Path::class.java).apply {
//            trySetAccessible()
//        }.invoke(classloader, originPath) as Manifest
    }

    override fun isDevelopment(): Boolean {
        return development
    }

    override fun getEntrypoint(): String {
        return provider.entrypoint
    }

    companion object {
//        private val IS_DEVELOPMENT = System.getProperty(SystemProperties.DEVELOPMENT, "false").toBoolean()
//        fun launch(args: Array<String>, type: EnvType) {
//            setupUncaughtExceptionHandler()
//            try {
//                val knot = Knot(type)
//                val cl = knot.init(args)
//                checkNotNull(knot.provider) { "Game provider was not initialized! (Knot#init(String[]))" }
//                knot.provider!!.launch(cl)
//            } catch (e: FormattedException) {
//                handleFormattedException(e)
//            }
//        }

        /**
         * Find game provider embedded into the Fabric Loader jar, best effort.
         *
         *
         * This is faster than going through service loader because it only looks at a single jar.
         */
//        private fun findEmbedddedGameProvider(): GameProvider? {
//            return try {
//                val flPath = UrlUtil.getCodeSource(Knot::class.java)
//                if (flPath == null || !flPath.fileName.toString().endsWith(".jar")) return null // not a jar
//                ZipFile(flPath.toFile()).use { zf ->
//                    val entry =
//                        zf.getEntry("META-INF/services/net.fabricmc.loader.impl.game.GameProvider")
//                            ?: return null // same file as used by service loader
//                    zf.getInputStream(entry).use { `is` ->
//                        var buffer = ByteArray(100)
//                        var offset = 0
//                        var len: Int
//                        while (`is`.read(buffer, offset, buffer.size - offset).also { len = it } >= 0) {
//                            offset += len
//                            if (offset == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
//                        }
//                        var content =
//                            String(buffer, 0, offset, StandardCharsets.UTF_8)
//                                .trim { it <= ' ' }
//                        if (content.indexOf('\n') >= 0) return null // potentially more than one entry -> bail out
//                        val pos = content.indexOf('#')
//                        if (pos >= 0) content = content.substring(0, pos).trim { it <= ' ' }
//                        if (!content.isEmpty()) {
//                            return Class.forName(content).getConstructor().newInstance() as GameProvider
//                        }
//                    }
//                }
//                null
//            } catch (e: IOException) {
//                throw RuntimeException(e)
//            } catch (e: ReflectiveOperationException) {
//                throw RuntimeException(e)
//            }
//        }

//        @JvmStatic
//        fun main(args: Array<String>) {
//            Knot(null).init(args)
//        }
//
//        init {
//            LoaderUtil.verifyNotInTargetCl(Knot::class.java)
//            if (IS_DEVELOPMENT) {
//                LoaderUtil.verifyClasspath()
//            }
//        }
    }
}

