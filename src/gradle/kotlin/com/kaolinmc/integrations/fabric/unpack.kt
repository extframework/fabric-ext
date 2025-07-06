package com.kaolinmc.integrations.fabric

import com.kaolinmc.archives.zip.ZipFinder
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.plus
import kotlin.io.path.name

public fun unpackFabricJar(
    jar: Path,
    name: String = jar.name,
): Map<String, Path> {
    val archive = ZipFinder.find(jar)

    return archive.reader.entries()
        .filter { !it.isDirectory }
        .filter { it.name.startsWith("META-INF/jars") }
        .filter { it.name.endsWith(".jar") }
        .toList()
        .flatMap {
            val childJar = Files.createTempFile("fabric-mod", ".jar")

            it.open().use { fin ->
                FileOutputStream(childJar.toFile()).use { fos ->
                    fin.copyTo(fos)
                }
            }

            val name = it.name.removePrefix("META-INF/jars/").removeSuffix(".jar").replace('.', '_') + ".jar"

            (unpackFabricJar(childJar, name)).toList()
        }.toMap() + mapOf(name to jar)
}
