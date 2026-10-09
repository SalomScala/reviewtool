package de.setsoftware.reviewtool.intellij;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.Icon;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;

/**
 * Factory methods for the simple actions used in the toolbars and context menus of the tool window
 * panels. The actions are updated on the EDT because their state depends on Swing components
 * (e.g. the selection in a tree).
 */
final class PanelActions {

    private PanelActions() {
    }

    /**
     * Creates an action that is enabled iff the condition holds (in context menus it is hidden when
     * disabled).
     */
    static DumbAwareAction action(String text, Icon icon, BooleanSupplier enabled, Runnable run) {
        return action(() -> text, icon, enabled, run);
    }

    /**
     * Creates an action with a text that can change (e.g. depending on a mode).
     */
    static DumbAwareAction action(Supplier<String> text, Icon icon, BooleanSupplier enabled, Runnable run) {
        return new DumbAwareAction(text.get(), null, icon) {
            @Override
            public void update(AnActionEvent e) {
                final boolean on = enabled.getAsBoolean();
                e.getPresentation().setText(text.get());
                e.getPresentation().setEnabled(on);
                if (e.isFromContextMenu()) {
                    e.getPresentation().setVisible(on);
                }
            }

            @Override
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(AnActionEvent e) {
                run.run();
            }
        };
    }

    /**
     * Makes the toolbar show the action's text next to its icon (for the central actions).
     */
    static <T extends DumbAwareAction> T withTextInToolbar(T action) {
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        return action;
    }

    /**
     * Creates a toggle action.
     */
    static DumbAwareToggleAction toggle(String text, Icon icon, BooleanSupplier getter, Consumer<Boolean> setter) {
        return new DumbAwareToggleAction(text, null, icon) {
            @Override
            public boolean isSelected(AnActionEvent e) {
                return getter.getAsBoolean();
            }

            @Override
            public void setSelected(AnActionEvent e, boolean state) {
                setter.accept(state);
            }

            @Override
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

}
