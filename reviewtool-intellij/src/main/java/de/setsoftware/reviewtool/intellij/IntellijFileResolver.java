package de.setsoftware.reviewtool.intellij;

import java.io.File;
import java.util.Collection;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

/**
 * Helper to resolve the file references used in the CoRT core (short file names from the review
 * remarks, absolute paths from the tour stops) to IntelliJ {@link VirtualFile}s.
 */
final class IntellijFileResolver {

    private IntellijFileResolver() {
    }

    /**
     * Resolves a short file name (file name without path, as stored in the review data) to a
     * virtual file in the project. If several files share the name, the first one is returned;
     * if none is found, null is returned.
     */
    static VirtualFile findByShortName(Project project, String shortName) {
        if (shortName == null || shortName.isEmpty()) {
            return null;
        }
        return ReadAction.compute(() -> {
            final Collection<VirtualFile> candidates =
                    FilenameIndex.getVirtualFilesByName(shortName, GlobalSearchScope.allScope(project));
            return candidates.isEmpty() ? null : candidates.iterator().next();
        });
    }

    /**
     * Resolves an absolute file (e.g. from {@code Stop.getAbsoluteFile()}) to a virtual file.
     */
    static VirtualFile findByAbsoluteFile(File file) {
        if (file == null) {
            return null;
        }
        final LocalFileSystem fs = LocalFileSystem.getInstance();
        final VirtualFile found = fs.findFileByIoFile(file);
        return found != null ? found : fs.refreshAndFindFileByIoFile(file);
    }

}
