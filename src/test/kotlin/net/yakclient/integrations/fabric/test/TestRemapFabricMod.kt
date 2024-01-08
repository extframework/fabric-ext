//package net.yakclient.integrations.fabric.test
//
//import net.yakclient.archive.mapper.ArchiveMapping
//import net.yakclient.archive.mapper.parsers.ProGuardMappingParser
//import net.yakclient.archive.mapper.parsers.tiny.TinyV2MappingsParser
//import net.yakclient.archive.mapper.transform.MappingDirection
//import net.yakclient.archive.mapper.transform.transformArchive
//import net.yakclient.archives.Archives
//import net.yakclient.components.extloader.api.mapping.MappingsProvider
//import net.yakclient.components.extloader.mapping.findShortest
//import net.yakclient.components.extloader.mapping.newMappingsGraph
//import java.io.FileOutputStream
//import java.net.URL
//import java.nio.file.Files
//import java.nio.file.Path
//import java.nio.file.StandardCopyOption
//import java.util.*
//import java.util.jar.JarEntry
//import java.util.jar.JarOutputStream
//import kotlin.test.Test
//
//class TestRemapFabricMod {
//    @Test
//    fun `Remap fabric jar`() {
////        val jarIn = Path.of("/Users/durganmcbroom/Downloads/create-fabric-0.5.1-d-build.1161+mc1.20.1.jar")
////        val mcJarIn = Path.of("/Users/durganmcbroom/IdeaProjects/yakclient/fabric-ext/src/test/resources/out.jar")
////        val ref = Archives.find(jarIn, Archives.Finders.ZIP_FINDER)
////        val mcRef = Archives.find(mcJarIn, Archives.Finders.ZIP_FINDER)
////
//        val intermediaryProvider = object : MappingsProvider {
//            override val fakeType: String = "official"
//            override val realType: String = "intermediary"
//
//            override fun forIdentifier(identifier: String): ArchiveMapping {
//                return TinyV2MappingsParser.parse(
//                    URL("https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/$identifier.tiny").openStream()
//                )
//            }
//        }
//
//        val officialProvider = object : MappingsProvider {
//            override val fakeType: String = "official"
//            override val realType: String = "official(deobf)"
//
//            override fun forIdentifier(identifier: String): ArchiveMapping {
//                check(identifier == "1.20.1")
//                return ProGuardMappingParser.parse(
//                    URL("https://piston-data.mojang.com/v1/objects/6c48521eed01fe2e8ecdadbd5ae348415f3c47da/client.txt").openStream()
//                )
//            }
//        }
//
//        val graph = newMappingsGraph(
//            listOf(
//                officialProvider,
//                intermediaryProvider
//            )
//        )
//
//        val provider = graph.findShortest(
//            "intermediary", "official(deobf)"
//        )
//
//        val mappings = provider.forIdentifier("1.20.1")
//
//        println("hasdf")
////
////        transformArchive(
////            ref,
////            listOf(
////                mcRef, ref
////            ),
////            mappings,
////            MappingDirection.TO_REAL
////        )
////
////        val createTempFile = Files.createTempDirectory(UUID.randomUUID().toString()).resolve("out.jar")
////        JarOutputStream(FileOutputStream(createTempFile.toFile())).use { target ->
////            ref.reader.entries().forEach { e ->
////                val entry = JarEntry(e.name)
////
////                target.putNextEntry(entry)
////
////                val eIn = e.resource.open()
////
////                //Stolen from https://stackoverflow.com/questions/1281229/how-to-use-jaroutputstream-to-create-a-jar-file
////                val buffer = ByteArray(1024)
////
////                while (true) {
////                    val count: Int = eIn.read(buffer)
////                    if (count == -1) break
////
////                    target.write(buffer, 0, count)
////                }
////
////                target.closeEntry()
////            }
////        }
////
////        println(createTempFile)
//    }
//}