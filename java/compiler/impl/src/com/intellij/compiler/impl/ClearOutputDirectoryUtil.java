package com.intellij.compiler.impl;

import java.io.*;
import java.util.*;

final class ClearOutputDirectoryUtil {

    static void addFileForClearOutputDirectory(Collection<File> filesToDelete, File outputDirectory) {
        File[] files = outputDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (deleteRecursively(filesToDelete, file)) {
                    filesToDelete.add(file);
                }
            }
        }
    }

    /**
     * @param filesToDelete add files/dir to be deleted in this list
     * @param file the file/dir to evaluate
     * @return {@code false} if the file must not be deleted, else {@code true} (in this case the file must not be added in the list)
     */
    public static boolean deleteRecursively(Collection<File> filesToDelete, File file) {
        String filePath = file.getPath().replace("\\", "/");
        boolean preserve = filePath.endsWith("/osgi.bnd") || //
                filePath.endsWith("-config-report.xml") || //
                filePath.endsWith("/classes/fonts.ser") || //
                filePath.contains("/classes/config-");
        if (preserve) {
            return false;
        }

        if (!file.isDirectory()) {
            return true;
        }

        boolean deleteAll = true;
        Collection<File> dirFilesToDelete = new ArrayList<File>();

        File[] dirFiles = file.listFiles();
        if (dirFiles != null) {
            for (File dirFile : dirFiles) {
                if (deleteRecursively(filesToDelete, dirFile)) {
                    dirFilesToDelete.add(dirFile);
                } else {
                    deleteAll = false;
                }
            }
        }

        if (!deleteAll) {
            filesToDelete.addAll(dirFilesToDelete);
        }

        return deleteAll;
    }

    private ClearOutputDirectoryUtil() {
    }
}
