package dev.extframework.integrations.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.LibClassifier;
import net.fabricmc.loader.impl.game.minecraft.McVersion;
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup;
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ExtFrameworkGameProvider implements GameProvider {
    private static final String[] ALLOWED_EARLY_CLASS_PREFIXES = { "org.apache.logging.log4j.", "com.mojang.util." };

    private static final Set<String> SENSITIVE_ARGS = new HashSet<>(Arrays.asList(
            // all lowercase without --
            "accesstoken",
            "clientid",
            "profileproperties",
            "proxypass",
            "proxyuser",
            "username",
            "userproperties",
            "uuid",
            "xuid"));

    private EnvType envType;
    private String entrypoint;
    private Arguments arguments;
    private final List<Path> gameJars = new ArrayList<>(2); // env game jar and potentially common game jar
    private Path realmsJar;
    private final Set<Path> logJars = new HashSet<>();
    private boolean log4jAvailable;
    private boolean slf4jAvailable;
    private final List<Path> miscGameLibraries = new ArrayList<>(); // libraries not relevant for loader's uses
    private Collection<Path> validParentClassPath; // computed parent class path restriction (loader+deps)
    protected McVersion versionData;
    private boolean hasModLoader = false;

    private final GameTransformer transformer = new GameTransformer() {
        @Override
        public byte[] transform(String className) {
            return super.transform(className);
        }
    };

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft";
    }

    @Override
    public String getRawGameVersion() {
        return versionData.getRaw();
    }

    @Override
    public String getNormalizedGameVersion() {
        return versionData.getNormalized();
    }

    @Override
    public Collection<GameProvider.BuiltinMod> getBuiltinMods() {
        BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
                .setName(getGameName());

        if (versionData.getClassVersion().isPresent()) {
            int version = versionData.getClassVersion().getAsInt() - 44;

            try {
                metadata.addDependency(new ModDependencyImpl(ModDependency.Kind.DEPENDS, "java", Collections.singletonList(String.format(Locale.ENGLISH, ">=%d", version))));
            } catch (VersionParsingException e) {
                throw new RuntimeException(e);
            }
        }

        return Collections.singletonList(new GameProvider.BuiltinMod(gameJars, metadata.build()));
    }

    public Path getGameJar() {
        return gameJars.get(0);
    }

    @Override
    public String getEntrypoint() {
        return entrypoint;
    }

    @Override
    public Path getLaunchDirectory() {
        if (arguments == null) {
            return Paths.get(".");
        }

        return getLaunchDirectory(arguments);
    }

    @Override
    public boolean isObfuscated() {
        return false; // generally yes...
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }


    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        final var delegateProvider = new MinecraftGameProvider();

        this.envType = launcher.getEnvironmentType();
        this.arguments = new Arguments();
        arguments.parse(args);

        try {
            LibClassifier<McLibrary> classifier = new LibClassifier<>(McLibrary.class, envType, delegateProvider);
            McLibrary envGameLib = envType == EnvType.CLIENT ? McLibrary.MC_CLIENT : McLibrary.MC_SERVER;
            Path commonGameJar = GameProviderHelper.getCommonGameJar();
            Path envGameJar = GameProviderHelper.getEnvGameJar(envType);
            boolean commonGameJarDeclared = commonGameJar != null;

            if (commonGameJarDeclared) {
                if (envGameJar != null) {
                    classifier.process(envGameJar, McLibrary.MC_COMMON);
                }

                classifier.process(commonGameJar);
            } else if (envGameJar != null) {
                classifier.process(envGameJar);
            }

            classifier.process(launcher.getClassPath());

            if (classifier.has(McLibrary.MC_BUNDLER)) {
//                BundlerProcessor.process(classifier);
            }

            envGameJar = classifier.getOrigin(envGameLib);
            if (envGameJar == null) return false;

            commonGameJar = classifier.getOrigin(McLibrary.MC_COMMON);

            if (commonGameJarDeclared && commonGameJar == null) {
                Log.warn(LogCategory.GAME_PROVIDER, "The declared common game jar didn't contain any of the expected classes!");
            }

            gameJars.add(envGameJar);

            if (commonGameJar != null && !commonGameJar.equals(envGameJar)) {
                gameJars.add(commonGameJar);
            }

            Path assetsJar = classifier.getOrigin(McLibrary.MC_ASSETS_ROOT);

            if (assetsJar != null && !assetsJar.equals(commonGameJar) && !assetsJar.equals(envGameJar)) {
                gameJars.add(assetsJar);
            }

            entrypoint = classifier.getClassName(envGameLib);
            realmsJar = classifier.getOrigin(McLibrary.REALMS);
            hasModLoader = classifier.has(McLibrary.MODLOADER);
            log4jAvailable = classifier.has(McLibrary.LOG4J_API) && classifier.has(McLibrary.LOG4J_CORE);
            slf4jAvailable = classifier.has(McLibrary.SLF4J_API) && classifier.has(McLibrary.SLF4J_CORE);
            boolean hasLogLib = log4jAvailable || slf4jAvailable;

            Log.configureBuiltin(hasLogLib, !hasLogLib);

            for (McLibrary lib : McLibrary.LOGGING) {
                Path path = classifier.getOrigin(lib);

                if (path != null) {
                    if (hasLogLib) {
                        logJars.add(path);
                    } else if (!gameJars.contains(path)) {
                        miscGameLibraries.add(path);
                    }
                }
            }

            miscGameLibraries.addAll(classifier.getUnmatchedOrigins());
            validParentClassPath = classifier.getSystemLibraries();
        } catch (IOException e) {
            throw ExceptionUtil.wrap(e);
        }

        // expose obfuscated jar locations for mods to more easily remap code from obfuscated to intermediary
        ObjectShare share = FabricLoaderImpl.INSTANCE.getObjectShare();
        share.put("fabric-loader:inputGameJar", gameJars.get(0)); // deprecated
        share.put("fabric-loader:inputGameJars", gameJars);
        if (realmsJar != null) share.put("fabric-loader:inputRealmsJar", realmsJar);

        String version = arguments.remove(Arguments.GAME_VERSION);
        if (version == null) version = System.getProperty(SystemProperties.GAME_VERSION);
        versionData = McVersionLookup.getVersion(gameJars, entrypoint, version);

        processArgumentMap(arguments, envType);

        return true;
    }

    private static void processArgumentMap(Arguments argMap, EnvType envType) {
        switch (envType) {
            case CLIENT:
                if (!argMap.containsKey("accessToken")) {
                    argMap.put("accessToken", "FabricMC");
                }

                if (!argMap.containsKey("version")) {
                    argMap.put("version", "Fabric");
                }

                String versionType = "";

                if (argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")) {
                    versionType = argMap.get("versionType") + "/";
                }

                argMap.put("versionType", versionType + "Fabric");

                if (!argMap.containsKey("gameDir")) {
                    argMap.put("gameDir", getLaunchDirectory(argMap).toAbsolutePath().normalize().toString());
                }

                break;
            case SERVER:
                argMap.remove("version");
                argMap.remove("gameDir");
                argMap.remove("assetsDir");
                break;
        }
    }

    private static Path getLaunchDirectory(Arguments argMap) {
        return Paths.get(argMap.getOrDefault("gameDir", "."));
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        launcher.setValidParentClassPath(validParentClassPath);

        // Load the logger libraries on the platform CL when in a unit test
        if (!logJars.isEmpty() && !Boolean.getBoolean(SystemProperties.UNIT_TEST)) {
            for (Path jar : logJars) {
                if (gameJars.contains(jar)) {
                    launcher.addToClassPath(jar, ALLOWED_EARLY_CLASS_PREFIXES);
                } else {
                    launcher.addToClassPath(jar);
                }
            }
        }

        setupLogHandler(launcher, true);

        transformer.locateEntrypoints(launcher, gameJars);
    }

    private void setupLogHandler(FabricLauncher launcher, boolean useTargetCl) {
        System.setProperty("log4j2.formatMsgNoLookups", "true"); // lookups are not used by mc and cause issues with older log4j2 versions

        try {
            final String logHandlerClsName;

            if (log4jAvailable) {
                logHandlerClsName = "net.fabricmc.loader.impl.game.minecraft.Log4jLogHandler";
            } else if (slf4jAvailable) {
                logHandlerClsName = "net.fabricmc.loader.impl.game.minecraft.Slf4jLogHandler";
            } else {
                return;
            }

            ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
            Class<?> logHandlerCls;

            if (useTargetCl) {
                Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
                logHandlerCls = launcher.loadIntoTarget(logHandlerClsName);
            } else {
                logHandlerCls = Class.forName(logHandlerClsName);
            }

            Log.init((LogHandler) logHandlerCls.getConstructor().newInstance());
            Thread.currentThread().setContextClassLoader(prevCl);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        if (arguments == null) return new String[0];

        String[] ret = arguments.toArray();
        if (!sanitize) return ret;

        int writeIdx = 0;

        for (int i = 0; i < ret.length; i++) {
            String arg = ret[i];

            if (i + 1 < ret.length
                    && arg.startsWith("--")
                    && SENSITIVE_ARGS.contains(arg.substring(2).toLowerCase(Locale.ENGLISH))) {
                i++; // skip value
            } else {
                ret[writeIdx++] = arg;
            }
        }

        if (writeIdx < ret.length) ret = Arrays.copyOf(ret, writeIdx);

        return ret;
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return transformer;
    }

    @Override
    public boolean canOpenErrorGui() {
        if (arguments == null || envType == EnvType.CLIENT) {
            return true;
        }

        List<String> extras = arguments.getExtraArgs();
        return !extras.contains("nogui") && !extras.contains("--nogui");
    }

    @Override
    public boolean hasAwtSupport() {
        // MC always sets -XstartOnFirstThread for LWJGL
        return !LoaderUtil.hasMacOs();
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {

    }

    @Override
    public void launch(ClassLoader loader) {

    }

}
