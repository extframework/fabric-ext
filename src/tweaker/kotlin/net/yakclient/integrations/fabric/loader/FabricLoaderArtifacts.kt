package net.yakclient.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.artifact.resolver.simple.maven.pom.PomRepository
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import com.durganmcbroom.resources.ResourceAlgorithm
import com.durganmcbroom.resources.VerifiedResource
import com.durganmcbroom.resources.toResource
import net.yakclient.common.util.readAsSha1
import java.net.URI
import java.net.URL


class FabricLoaderMetadataHandler(settings: SimpleMavenRepositorySettings) : SimpleMavenMetadataHandler(
    settings
) {
    override fun requestMetadata(desc: SimpleMavenDescriptor): Job<SimpleMavenArtifactMetadata> {
        // If it's not the fabric loader, resolve it and then add fabric and neoforge as possible repositories because
        // 9 times out of 10 their poms didn't include that necessary piece of metadata.
        if (!(desc.group == "net.fabricmc" && desc.artifact == "fabric-loader")) return job {
            val metadata = super.requestMetadata(desc)().merge()

            SimpleMavenArtifactMetadata(
                metadata.descriptor,
                metadata.resource,
                metadata.children.map {
                    it.copy(
                        candidates = it.candidates + listOf(
                            SimpleMavenRepositoryStub(
                                PomRepository(null, "fabric", "https://maven.fabricmc.net/"),
                                true
                            ),
                            SimpleMavenRepositoryStub(
                                PomRepository(
                                    null,
                                    "neoforge",
                                    "https://maven.neoforged.net/releases"
                                ), true
                            ),
                        )
                    )
                }
            )
        }

        //julian podzilni
        return job {
            // All the dependencies fabric-loader needs, yes i know this is awful, but its
            // not included in the fabric-loader metadata. Would eventually like to create
            // a patch repository with this info.
            val dependencies = listOf(
                "net.fabricmc:mapping-io:0.5.0",
                "net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5",
                "net.fabricmc:access-widener:2.1.0",
                "org.ow2.asm:asm-commons:9.6",
                "cpw.mods:modlauncher:10.1.9", // for sponge-mixin,
                "net.minecraft:launchwrapper:1.12",
//                "io.github.llamalad7:mixinextras-fabric:0.3.2"
            )

            val mavenChildInfoList = dependencies.map { dependency ->
                SimpleMavenChildInfo(
                    SimpleMavenDescriptor.parseDescription(dependency)!!,
                    listOf(
                        SimpleMavenRepositoryStub(PomRepository(null, "fabric", "https://maven.fabricmc.net/"), true),
                        SimpleMavenRepositoryStub(
                            PomRepository(
                                null,
                                "neoforge",
                                "https://maven.neoforged.net/releases",
                            ), true
                        ),
                        SimpleMavenRepositoryStub(
                            PomRepository(
                                null,
                                "minecraft",
                                "https://libraries.minecraft.net"
                            ), true
                        ),
                    ),
                    "runtime"
                )
            }

            SimpleMavenArtifactMetadata(
                desc,
                VerifiedResource(
                    URL("https://maven.fabricmc.net/net/fabricmc/fabric-loader/${desc.version}/fabric-loader-${desc.version}.jar").toResource(),
                    ResourceAlgorithm.SHA1,
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