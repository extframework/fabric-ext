package net.yakclient.integrations.fabric.util

import net.yakclient.archives.ArchiveReference
import net.yakclient.common.util.make
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

fun ArchiveReference.write(path: Path) {
    path.make()
    JarOutputStream(FileOutputStream(path.toFile())).use { target ->
        reader.entries().forEach { e ->
            val entry = JarEntry(e.name)

            target.putNextEntry(entry)

            val eIn = e.resource.open()

            //Stolen from https://stackoverflow.com/questions/1281229/how-to-use-jaroutputstream-to-create-a-jar-file
            val buffer = ByteArray(1024)

            while (true) {
                val count: Int = eIn.read(buffer)
                if (count == -1) break

                target.write(buffer, 0, count)
            }

            target.closeEntry()
        }
        target.close()
    }
}