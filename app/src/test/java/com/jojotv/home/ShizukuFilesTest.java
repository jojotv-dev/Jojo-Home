package com.jojotv.home;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class ShizukuFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void copyPreservesBinaryDataAcrossBufferBoundaries() throws Exception {
        byte[] data = new byte[131073];
        for (int index = 0; index < data.length; index++) data[index] = (byte) index;
        File target = new File(temporary.getRoot(), "video.mp4");

        ShizukuFiles.copyToFile(new ByteArrayInputStream(data), target);

        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void failedReadRemovesPartialCacheFile() throws Exception {
        File target = new File(temporary.getRoot(), "video.mp4");
        InputStream broken = new InputStream() {
            private boolean delivered;
            @Override public int read() throws IOException { throw new IOException("Read failed"); }
            @Override public int read(byte[] buffer) throws IOException {
                if (delivered) throw new IOException("Read failed");
                delivered = true;
                buffer[0] = 42;
                return 1;
            }
        };

        assertThrows(IOException.class, () -> ShizukuFiles.copyToFile(broken, target));

        assertFalse(target.exists());
    }

    @Test
    public void mutablePathAllowsSharedAndRemovableStorage() throws Exception {
        assertEquals("/storage/emulated/0/Movies/film.mkv",
                ShizukuFiles.mutablePath("/storage/emulated/0/Movies/film.mkv"));
        assertEquals("/storage/ABCD-1234/Movies/film.mkv",
                ShizukuFiles.mutablePath("/storage/ABCD-1234/Movies/film.mkv"));
    }

    @Test
    public void mutablePathRejectsStorageRootSystemPathsAndTraversal() {
        assertThrows(IOException.class, () -> ShizukuFiles.mutablePath("/storage/emulated/0"));
        assertThrows(IOException.class, () -> ShizukuFiles.mutablePath("/data/system/packages.xml"));
        assertThrows(IOException.class, () -> ShizukuFiles.mutablePath("/storage/emulated/0/../system"));
    }
}
