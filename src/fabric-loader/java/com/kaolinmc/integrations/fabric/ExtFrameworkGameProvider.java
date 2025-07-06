package com.kaolinmc.integrations.fabric;

import com.kaolinmc.core.minecraft.api.MinecraftAppApi;
import com.kaolinmc.core.minecraft.environment.MinecraftAppKt;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.minecraft.McVersion;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.LoaderUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ExtFrameworkGameProvider implements GameProvider {
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
    private Arguments arguments;
    private final List<Path> gameJars = new ArrayList<>(2); // env game jar and potentially common game jar
    protected McVersion versionData;
    private final MinecraftAppApi minecraftAppApi;

    public ExtFrameworkGameProvider() {
        this.minecraftAppApi = MinecraftAppKt.getMinecraft(FabricIntegrationTweaker.Companion.getTweakerEnv());
    }

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

    @Override
    public String getEntrypoint() {
        return minecraftAppApi.getMainClass();
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
        return false; // generally, yes... here... no
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
        this.envType = launcher.getEnvironmentType();
        this.arguments = new Arguments();
        arguments.parse(args);

        // expose obfuscated jar locations for mods to more easily remap code from obfuscated to intermediary
        gameJars.add(minecraftAppApi.getGameJar());

        ObjectShare share = FabricLoaderImpl.INSTANCE.getObjectShare();
        share.put("fabric-loader:inputGameJar", gameJars.get(0)); // deprecated
        share.put("fabric-loader:inputGameJars", gameJars);

        versionData = new McVersion.Builder()
                .setId(minecraftAppApi.getVersion())
                .setName(minecraftAppApi.getVersion())
                .setVersion(minecraftAppApi.getVersion())
                .setRelease(minecraftAppApi.getVersion())
                .build();

        return true;
    }

    private static Path getLaunchDirectory(Arguments argMap) {
        return Paths.get(argMap.getOrDefault("gameDir", "."));
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        launcher.setValidParentClassPath(gameJars);

        transformer.locateEntrypoints(launcher, gameJars);
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
