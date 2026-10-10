package de.setsoftware.reviewtool.intellij;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.concurrency.AppExecutorUtil;

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
     * if none is found, null is returned. Uses the file index, so it must not be called on the EDT
     * (see {@link #findByShortNamesAsync}).
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
     * Resolves the given short file names in a background read action (the file index must not be
     * used on the EDT and needs the indexes to be ready) and passes the found files (by name; names
     * without a file are missing) to the consumer on the EDT.
     */
    static void findByShortNamesAsync(Project project, Collection<String> shortNames,
            Consumer<Map<String, VirtualFile>> consumerOnEdt) {
        ReadAction.nonBlocking(() -> {
            final Map<String, VirtualFile> ret = new HashMap<>();
            for (final String name : shortNames) {
                final VirtualFile file = findByShortName(project, name);
                if (file != null) {
                    ret.put(name, file);
                }
            }
            return ret;
        })
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), consumerOnEdt)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Creates a descriptor to open the given file at the start of the given (0-based) line. The
     * descriptor is created from the offset, because the variant with line and column determines the
     * code style (and thereby the PSI) of the file, which is a slow operation on the EDT.
     */
    static OpenFileDescriptor descriptorForLine(Project project, VirtualFile file, int line) {
        final Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || document.getLineCount() == 0) {
            return new OpenFileDescriptor(project, file, 0);
        }
        final int boundedLine = Math.max(0, Math.min(line, document.getLineCount() - 1));
        return new OpenFileDescriptor(project, file, document.getLineStartOffset(boundedLine));
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
