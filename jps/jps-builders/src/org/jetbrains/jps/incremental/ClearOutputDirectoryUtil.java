package org.jetbrains.jps.incremental;

import java.io.*;
import java.util.*;

final class ClearOutputDirectoryUtil {

  static void addFileForClearOutputDirectory(Collection<File> filesToDelete, File outputDirectory) {
    deleteRecursively(filesToDelete, outputDirectory);
    File[] files = outputDirectory.listFiles();
    if (files != null) {
      for (File file : files) {
        deleteRecursively(filesToDelete, file);
      }
    }
  }

  /**
   * @param filesToDelete add files/dir to be deleted in this list
   * @param file          the file/dir to evaluate
   * @return {@code false} if the file must not be deleted, else {@code true} (in this case the file must not be added in the list)
   */
  static boolean deleteRecursively(Collection<File> filesToDelete, File file) {
    String filePath = file.getPath().replace("\\", "/");
    boolean preserve = filePath.endsWith("/osgi.bnd") || //
                       filePath.endsWith("-config-report.xml") || //
                       filePath.endsWith("/classes/fonts.ser") || //
                       filePath.contains("/classes/config-") || //
                       filePath.endsWith("/dev.composite") || //
                       filePath.endsWith("/dev.composite.ignored") || //
                       filePath.endsWith("/framework-dev.conf");
    if (preserve) {
      return false;
    }

    boolean canDelete = true;
    if (file.isDirectory()) {
      File[] dirFiles = file.listFiles();
      if (dirFiles != null) {
        for (File dirFile : dirFiles) {
          if (!deleteRecursively(filesToDelete, dirFile)) {
            canDelete = false;
          }
        }
      }
    }

    if (canDelete) {
      if (!file.delete()) {
        filesToDelete.add(file);
      }
    }

    return canDelete;
  }

  private ClearOutputDirectoryUtil() {
  }
}
