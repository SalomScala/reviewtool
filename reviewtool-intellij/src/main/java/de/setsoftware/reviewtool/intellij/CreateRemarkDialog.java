package de.setsoftware.reviewtool.intellij;

import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.model.api.PositionReference;
import de.setsoftware.reviewtool.model.remarks.RemarkType;

/**
 * Dialog for the creation of a review remark, the IntelliJ counterpart of the Eclipse
 * "CreateRemarkDialog": the kind of remark, the text (prefilled with the editor selection) and what
 * the remark refers to (the line, the whole file or the review as a whole) are entered in one
 * dialog. Typing the first letter of a kind in the kind combo box selects it and moves the focus to
 * the text, and Ctrl+Enter adds the remark, so remarks can be entered with the keyboard only.
 */
final class CreateRemarkDialog extends DialogWrapper {

    private static final String LAST_TYPE_KEY = "de.setsoftware.reviewtool.lastRemarkType";

    /**
     * Combo box item for a remark type. The label starts with the (German) header used in the
     * serialized review remarks, so the first letters are the same as in the Eclipse dialog.
     */
    private static final class TypeItem {
        private final RemarkType type;
        private final String label;

        TypeItem(RemarkType type, String label) {
            this.type = type;
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    /**
     * Combo box item for a position reference.
     */
    private static final class ReferenceItem {
        private final PositionReference reference;
        private final String label;

        ReferenceItem(PositionReference reference, String label) {
            this.reference = reference;
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    private static final TypeItem[] TYPES = {
        new TypeItem(RemarkType.MUST_FIX, "muss - must be fixed"),
        new TypeItem(RemarkType.CAN_FIX, "kann - can be fixed (optional)"),
        new TypeItem(RemarkType.ALREADY_FIXED, "direkt eingepflegt - already fixed by the reviewer"),
        new TypeItem(RemarkType.POSITIVE, "positiv - positive feedback"),
        new TypeItem(RemarkType.TEMPORARY, "temporärer Marker - temporary note, resolve before ending the review"),
        new TypeItem(RemarkType.OTHER, "sonstige Anmerkungen - other remark"),
    };

    private final JComboBox<TypeItem> typeCombo = new JComboBox<>(TYPES);
    private final JBTextArea textArea = new JBTextArea(6, 50);
    private final JComboBox<ReferenceItem> referenceCombo = new JComboBox<>();

    /**
     * Creates the dialog.
     * @param location A description of the location the remark is created for (e.g. "Foo.java:12"),
     *      or null if there is no file.
     * @param prefillText The text the remark is prefilled with (e.g. the selected text), may be empty.
     * @param allowedReferences The references the user can choose from (at least one).
     */
    CreateRemarkDialog(Project project, String location, String prefillText, Set<PositionReference> allowedReferences) {
        super(project);
        this.setTitle(location == null ? "Add Review Remark" : "Add Review Remark - " + location);
        this.setOKButtonText("Add Remark");

        this.textArea.setLineWrap(true);
        this.textArea.setWrapStyleWord(true);
        this.textArea.setText(prefillText == null ? "" : prefillText);

        final List<ReferenceItem> references = new ArrayList<>();
        // the labels name the target, because the dialog title is not always visible
        final String fileName = location == null ? null : location.replaceFirst(":\\d+$", "");
        if (allowedReferences.contains(PositionReference.LINE)) {
            references.add(new ReferenceItem(PositionReference.LINE, "This line (" + location + ")"));
        }
        if (allowedReferences.contains(PositionReference.FILE)) {
            references.add(new ReferenceItem(PositionReference.FILE, "Whole file (" + fileName + ")"));
        }
        if (allowedReferences.contains(PositionReference.GLOBAL)) {
            references.add(new ReferenceItem(PositionReference.GLOBAL, "Global (whole review)"));
        }
        for (final ReferenceItem item : references) {
            this.referenceCombo.addItem(item);
        }
        this.referenceCombo.setEnabled(references.size() > 1);

        this.selectLastUsedType();
        this.installKeyboardShortcuts();
        this.init();
    }

    private void selectLastUsedType() {
        final String last = PropertiesComponent.getInstance().getValue(LAST_TYPE_KEY, RemarkType.MUST_FIX.name());
        for (final TypeItem item : TYPES) {
            if (item.type.name().equals(last)) {
                this.typeCombo.setSelectedItem(item);
            }
        }
    }

    private void installKeyboardShortcuts() {
        // typing the first letter of a kind selects it and continues with the text
        this.typeCombo.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                final char c = Character.toLowerCase(e.getKeyChar());
                for (final TypeItem item : TYPES) {
                    if (Character.toLowerCase(item.label.charAt(0)) == c) {
                        CreateRemarkDialog.this.typeCombo.setSelectedItem(item);
                        CreateRemarkDialog.this.typeCombo.hidePopup();
                        CreateRemarkDialog.this.textArea.requestFocusInWindow();
                        e.consume();
                        return;
                    }
                }
            }
        });

        // Enter inserts a line break in the text, Ctrl+Enter (Cmd+Enter on macOS) adds the remark
        final AbstractAction submit = new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                CreateRemarkDialog.this.getOKAction().actionPerformed(e);
            }
        };
        this.textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "cortSubmit");
        this.textArea.getInputMap().put(KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "cortSubmit");
        this.textArea.getActionMap().put("cortSubmit", submit);
    }

    @Override
    protected JComponent createCenterPanel() {
        final JBLabel hint = new JBLabel(
                "Type the first letter to choose the kind (m, k, d, p, t, s). Ctrl+Enter adds the remark.");
        hint.setFontColor(UIUtil.FontColor.BRIGHTER);
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Kind:", this.typeCombo)
                .addLabeledComponentFillVertically("Remark:", new JBScrollPane(this.textArea))
                .addLabeledComponent("Refers to:", this.referenceCombo)
                .addComponent(hint)
                .getPanel();
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return this.typeCombo;
    }

    @Override
    protected ValidationInfo doValidate() {
        if (this.getRemarkText().isEmpty()) {
            return new ValidationInfo("Please enter a text for the remark.", this.textArea);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        PropertiesComponent.getInstance().setValue(LAST_TYPE_KEY, this.getRemarkType().name());
        super.doOKAction();
    }

    @Override
    protected String getDimensionServiceKey() {
        return "de.setsoftware.reviewtool.CreateRemarkDialog";
    }

    String getRemarkText() {
        return this.textArea.getText().trim();
    }

    RemarkType getRemarkType() {
        return ((TypeItem) this.typeCombo.getSelectedItem()).type;
    }

    PositionReference getReference() {
        final ReferenceItem item = (ReferenceItem) this.referenceCombo.getSelectedItem();
        return item == null ? PositionReference.GLOBAL : item.reference;
    }

}
