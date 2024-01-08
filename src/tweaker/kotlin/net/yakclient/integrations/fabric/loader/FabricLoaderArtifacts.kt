package net.yakclient.integrations.fabric.loader

import arrow.core.continuations.either
import com.durganmcbroom.artifact.resolver.MetadataRequestException
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.artifact.resolver.simple.maven.layout.SimpleMavenDefaultLayout
import com.durganmcbroom.artifact.resolver.simple.maven.pom.PomRepository
import net.yakclient.common.util.readAsSha1
import java.net.URI




class FabricLoaderMetadataHandler(settings: SimpleMavenRepositorySettings) : SimpleMavenMetadataHandler(
    settings
) {
    override fun requestMetadata(desc: SimpleMavenDescriptor): arrow.core.Either<MetadataRequestException, SimpleMavenArtifactMetadata> {
        if (!(desc.group == "net.fabricmc" && desc.artifact == "fabric-loader")) return either.eager {
            val metadata = super.requestMetadata(desc).bind()

            SimpleMavenArtifactMetadata(
                metadata.descriptor,
                metadata.resource,
                metadata.children.map {
                    it.copy(
                        candidates = it.candidates + listOf(
                            SimpleMavenRepositoryStub(PomRepository(null, "fabric", "https://maven.fabricmc.net/")),
                            SimpleMavenRepositoryStub(
                                PomRepository(
                                    null,
                                    "neoforge",
                                    "https://maven.neoforged.net/releases"
                                )
                            ),
                        )
                    )
                }
            )
        }
        //julian podzilni
        return either.eager {
            val dependencies = listOf(
                "net.fabricmc:mapping-io:0.5.0",
                "net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5",
                "net.fabricmc:access-widener:2.1.0",
                "org.ow2.asm:asm-commons:9.6",
                "cpw.mods:modlauncher:10.1.9", // for sponge-mixin,
                "net.minecraft:launchwrapper:1.12",
                "io.github.llamalad7:mixinextras-fabric:0.3.2"
            )

            val mavenChildInfoList = dependencies.map { dependency ->
                SimpleMavenChildInfo(
                    SimpleMavenDescriptor.parseDescription(dependency)!!,
                    listOf(
//                        SimpleMavenRepositoryStub(PomRepository(null, "maven-central", "https://repo1.maven.org/maven2/")),
                        SimpleMavenRepositoryStub(PomRepository(null, "fabric", "https://maven.fabricmc.net/")),
                        SimpleMavenRepositoryStub(
                            PomRepository(
                                null,
                                "neoforge",
                                "https://maven.neoforged.net/releases",

                                )
                        ),
                        SimpleMavenRepositoryStub(
                            PomRepository(
                                null,
                                "minecraft",
                                "https://libraries.minecraft.net"
                            )
                        ),
                    ),
                    "runtime"
                )
            }

            SimpleMavenArtifactMetadata(
                desc,
                HashedResource(
                    "SHA1",
                    "https://maven.fabricmc.net/net/fabricmc/fabric-loader/${desc.version}/fabric-loader-${desc.version}.jar",
                    URI.create("https://maven.fabricmc.net/net/fabricmc/fabric-loader/${desc.version}/fabric-loader-${desc.version}.jar.sha1")
                        .readAsSha1()
                ),
                mavenChildInfoList
            )
        }
    }
}

object FabricRepositoryFactory :
    RepositoryFactory<SimpleMavenRepositorySettings, SimpleMavenArtifactRequest, SimpleMavenArtifactStub, SimpleMavenArtifactReference, SimpleMavenArtifactRepository> {
    override fun createNew(settings: SimpleMavenRepositorySettings): SimpleMavenArtifactRepository {
        return SimpleMavenArtifactRepository(
            this,
            FabricLoaderMetadataHandler(settings),
            settings
        )
    }
}