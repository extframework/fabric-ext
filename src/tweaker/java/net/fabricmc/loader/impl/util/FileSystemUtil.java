package net.fabricmc.loader.impl.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipError;

public final class FileSystemUtil {
    private static final Map<String, String> jfsArgsCreate = Collections.singletonMap("create", "true");
    private static final Map<String, String> jfsArgsEmpty = Collections.emptyMap();

    private static final List<FileSystem> openSystems = new ArrayList<>();

    private FileSystemUtil() {
    }

    public static FileSystemDelegate getJarFileSystem(Path path, boolean create) throws IOException {
        return getJarFileSystem(path.toUri(), create);
    }

    public static void closeAll() throws IOException {
        for (FileSystem openSystem : openSystems) {
//            openSystem.close();
        }
    }

    public static FileSystemDelegate getJarFileSystem(URI uri, boolean create) throws IOException {
        URI jarUri;
        try {
            jarUri = new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        boolean opened = false;
        FileSystem ret;

        try {
            ret = FileSystems.getFileSystem(jarUri);

            if (!ret.isOpen()) {
                ret.close();
                throw new FileSystemNotFoundException();
            }
        } catch (FileSystemNotFoundException var9) {
            try {
                ret = FileSystems.newFileSystem(jarUri, create ? jfsArgsCreate : jfsArgsEmpty);
                opened = true;
            } catch (FileSystemAlreadyExistsException var7) {
                ret = FileSystems.getFileSystem(jarUri);
            } catch (ZipError | IOException e) {
                throw new IOException("Error accessing " + uri + ": " + e, e);
            }
        }

        openSystems.add(ret);

        return new FileSystemDelegate(ret, opened);
    }

    public static class FileSystemDelegate implements AutoCloseable {
        private final FileSystem fileSystem;
        private final boolean owner;

        public FileSystemDelegate(FileSystem fileSystem, boolean owner) {
            this.fileSystem = fileSystem;
            this.owner = owner;
        }

        public FileSystem get() {
            return this.fileSystem;
        }

        public void close() throws IOException {
            if (this.owner) {
                // TODO This is a bad solution
//                this.fileSystem.close();
            }

        }
    }
}
