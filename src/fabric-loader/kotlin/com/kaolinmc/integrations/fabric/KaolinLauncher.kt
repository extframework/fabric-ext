package com.kaolinmc.integrations.fabric

import com.kaolinmc.archives.Archives
import com.kaolinmc.archives.zip.classLoaderToArchive
import com.kaolinmc.boot.loader.*
import com.kaolinmc.common.util.readInputStream
import com.kaolinmc.common.util.toBytes
import com.kaolinmc.core.app.TargetLinker
import com.kaolinmc.core.app.api.ApplicationTarget
import com.kaolinmc.core.minecraft.environment.minecraft
import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.fabricmc.loader.impl.FormattedException
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.launch.FabricMixinBootstrap
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot
import net.fabricmc.loader.impl.util.LoaderUtil
import net.fabricmc.loader.impl.util.log.Log
import net.fabricmc.loader.impl.util.log.LogCategory
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.ProtectionDomain
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Manifest

class KaolinLauncher(
    private var envType: EnvType,
    private var development: Boolean// = false
) : FabricLauncherBase() {
    private var properties: Map<String, Any> = HashMap()
    private val classloader = KnotClassLoaderReplacement() //Any // KnotClassloaderInterface

    private class KnotClassLoaderReplacement : MutableClassLoader(
        name = "Knot Classloader replacement",
        classes = MutableClassProvider(mutableListOf(ArchiveClassProvider(classLoaderToArchive(FabricIntegrationTweaker.tweakerEnv[TargetLinker].targetLoader)))),
        resources = MutableResourceProvider(
            mutableListOf(
                ArchiveResourceProvider(
                    classLoaderToArchive(
                        FabricIntegrationTweaker.tweakerEnv[TargetLinker].targetLoader
                    )
                )
            )
        ),
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
            ).apply { trySetAccessible() }.invoke(null) as? IMixinTransformer

            val source = sourceProvider.findSource(name)
            val cls = source?.toBytes()?.let {
                transformer?.transformClassBytes(name, name, it) ?: it
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
    internal lateinit var provider: ExtFrameworkGameProvider
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
        provider = createGameProvider(args, )

        Log.finishBuiltinConfig()
        Log.info(
            LogCategory.GAME_PROVIDER,
            "Loading %s %s with Kaolin/Fabric Loader %s",
            provider.gameName,
            provider.rawGameVersion,
            FabricLoaderImpl.VERSION
        )

        provider.initialize(this)

        Thread.currentThread().setContextClassLoader(classloader)

        val loader = FabricLoaderImpl.INSTANCE
        loader.gameProvider = provider

        development = true
        loader.load()
//        development = false
        loader.freeze()

        FabricMixinBootstrap.init(environmentType, loader)
        development = false
        finishMixinBootstrapping()

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

    private fun createGameProvider(
        args: Array<String>,
    ): ExtFrameworkGameProvider {
        val provider = ExtFrameworkGameProvider()

        provider.locateGame(this, args)
        return provider
    }

    override fun getTargetNamespace(): String {
        return  "named"
    }

    override fun getClassPath(): List<Path> {
        val reference : ApplicationTarget = FabricIntegrationTweaker.tweakerEnv[ApplicationTarget]
        return listOf(reference.path)
    }

    override fun addToClassPath(path: Path, vararg allowedPrefixes: String) {
        Log.debug(LogCategory.KNOT, "Adding $path to classpath.")

        setAllowedPrefixes(path, *allowedPrefixes)

        val archive = Archives.find(path, Archives.Finders.ZIP_FINDER)
        classloader.addSources(ArchiveSourceProvider(archive))
        classloader.addResources(ArchiveResourceProvider(archive))
    }


    override fun setAllowedPrefixes(path: Path, vararg prefixes: String) {
        //
    }

    override fun setValidParentClassPath(paths: Collection<Path>) {
    }

    override fun getEnvironmentType(): EnvType {
        return envType
    }

    override fun isClassLoaded(name: String): Boolean {
        return classloader.isClassLoaded(name)
    }

    @Throws(ClassNotFoundException::class)
    override fun loadIntoTarget(name: String): Class<*> {
        return classloader.loadClass(name)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        return classloader.getResourceAsStream(name)
    }

    override fun getTargetClassLoader(): ClassLoader {
        return classloader
    }

    @Throws(IOException::class)
    override fun getClassByteArray(name: String, runTransformers: Boolean): ByteArray? {
        // SPECIFICALLY IGNORING TRANSFORMATIONS MADE BY THE INSTRUMENTED APP TARGET
        val targetRef =
            FabricIntegrationTweaker.tweakerEnv.minecraft.node.handle?.classloader
            ?.getResource(name.replace('.', '/') + ".class")?.readBytes()

        return targetRef ?: classloader.getResourceAsStream(
            name.replace(
                '.',
                '/'
            ) + ".class"
        )?.readInputStream()
    }

    override fun getManifest(originPath: Path): Manifest? {
        return null
    }

    override fun isDevelopment(): Boolean {
        return development
    }

    override fun getEntrypoint(): String {
        return provider.entrypoint
    }
}

