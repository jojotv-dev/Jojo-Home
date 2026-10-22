package com.jojotv.home;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

public final class AppExportHelper {
    private AppExportHelper() {}

    public static String archiveName(String packageName, String label) {
        String base = (label == null || label.trim().isEmpty()) ? "" : label.trim();
        String safeLabel = (base == null || base.trim().isEmpty())
                ? ""
                : base.trim().replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-").replaceAll("(^-|-$)", "");
        String safePackage = packageName == null || packageName.trim().isEmpty()
                ? "app"
                : packageName.trim().replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-").replaceAll("(^-|-$)", "");
        if (safePackage.isEmpty()) safePackage = "app";
        String combined = safeLabel.isEmpty() ? safePackage : safeLabel + "_" + safePackage;
        return combined.toLowerCase(Locale.ROOT) + ".zip";
    }

    public static File createArchive(File destinationDir, String packageName, String label, File apkFile, String appName) throws IOException {
        File archive = new File(destinationDir, archiveName(packageName, label));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            addFile(zip, apkFile, "app.apk");
            addText(zip, "metadata.json", "{\"package\":\"" + packageName + "\",\"name\":\"" + appName + "\"}");
        }
        return archive;
    }

    public static File createFileArchive(File destinationDir, File source) throws IOException {
        if (source == null || !source.exists()) throw new IOException("Fichier introuvable");
        File archive = uniqueArchive(destinationDir, source.getName());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            addLocalTree(zip, source, cleanEntryName(source.getName()));
        }
        return archive;
    }

    public static File createSmbArchive(File destinationDir, SmbFile source) throws IOException {
        if (source == null || !source.exists()) throw new IOException("Fichier NAS introuvable");
        File archive = uniqueArchive(destinationDir, source.getName());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            addSmbTree(zip, source, cleanEntryName(source.getName()));
        }
        return archive;
    }

    private static File uniqueArchive(File destinationDir, String sourceName) throws IOException {
        if (!destinationDir.exists() && !destinationDir.mkdirs()) throw new IOException("Dossier d’export indisponible");
        String base = sourceName == null ? "export" : sourceName.replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) base = base.substring(0, base.length() - 4);
        if (base.trim().isEmpty()) base = "export";
        File archive = new File(destinationDir, base + ".zip");
        for (int index = 2; archive.exists(); index++) archive = new File(destinationDir, base + " (" + index + ").zip");
        return archive;
    }

    private static String cleanEntryName(String name) {
        String value = name == null ? "export" : name.replace('\\', '_').replace('/', '_');
        return value.isEmpty() ? "export" : value;
    }

    private static void addLocalTree(ZipOutputStream zip, File source, String entryName) throws IOException {
        if (source.isDirectory()) {
            String directoryEntry = entryName.endsWith("/") ? entryName : entryName + "/";
            zip.putNextEntry(new ZipEntry(directoryEntry));
            zip.closeEntry();
            File[] children = source.listFiles();
            if (children != null) for (File child : children) addLocalTree(zip, child, directoryEntry + cleanEntryName(child.getName()));
            return;
        }
        addFile(zip, source, entryName);
    }

    private static void addSmbTree(ZipOutputStream zip, SmbFile source, String entryName) throws IOException {
        if (source.isDirectory()) {
            String directoryEntry = entryName.endsWith("/") ? entryName : entryName + "/";
            zip.putNextEntry(new ZipEntry(directoryEntry));
            zip.closeEntry();
            for (SmbFile child : source.listFiles()) addSmbTree(zip, child, directoryEntry + cleanEntryName(child.getName()));
            return;
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = new SmbFileInputStream(source)) {
            zip.putNextEntry(new ZipEntry(entryName));
            int read;
            while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
            zip.closeEntry();
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String entryName) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Fichier introuvable : " + entryName);
        byte[] buffer = new byte[8192];
        try (InputStream input = new FileInputStream(file)) {
            zip.putNextEntry(new ZipEntry(entryName));
            int read;
            while ((read = input.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
            }
            zip.closeEntry();
        }
    }

    private static void addText(ZipOutputStream zip, String entryName, String content) throws IOException {
        byte[] bytes = content.getBytes("UTF-8");
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(bytes, 0, bytes.length);
        zip.closeEntry();
    }
}
