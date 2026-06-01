package com.tiltus.beu.plugin.project;

import com.intellij.ide.wizard.AbstractNewProjectWizardStep;
import com.intellij.ide.wizard.NewProjectWizardStep;
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.dsl.builder.AlignX;
import com.intellij.ui.dsl.builder.Panel;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BevyNewProjectWizard implements LanguageGeneratorNewProjectWizard {
    @Override
    public @NotNull String getName() {
        return "Bevy";
    }

    @Override
    public Icon getIcon() {
        return IconLoader.getIcon("/icons/bevyProject16.svg", BevyNewProjectWizard.class);
    }

    @Override
    public int getOrdinal() {
        return 890;
    }

    @Override
    public NewProjectWizardStep createStep(NewProjectWizardStep parent) {
        return new Step(parent);
    }

    private static final class Step extends AbstractNewProjectWizardStep {
        private final BevyProjectGeneratorPeer projectGeneratorPeer = new BevyProjectGeneratorPeer();
        private final BevyDirectoryProjectGenerator projectGenerator = new BevyDirectoryProjectGenerator();
        private final JComponent settingsComponent = projectGeneratorPeer.getComponent();

        private Step(NewProjectWizardStep parent) {
            super(parent);
        }

        @Override
        public void setupUI(@NotNull Panel panel) {
            panel.row((JLabel) null, row -> {
                row.cell(settingsComponent)
                        .align(AlignX.FILL)
                        .validationInfo((builder, component) -> projectGeneratorPeer.validate());
                return Unit.INSTANCE;
            });
        }

        @Override
        public void setupProject(@NotNull Project project) {
            Path projectDir = getContext().getProjectDirectory();
            if (projectDir == null) {
                return;
            }

            try {
                Files.createDirectories(projectDir);
            } catch (Exception ignored) {
                return;
            }

            VirtualFile baseDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir);
            if (baseDir == null) {
                return;
            }

            ValidationInfo validation = projectGeneratorPeer.validate();
            if (validation != null) {
                return;
            }

            BevyProjectSettings settings = projectGeneratorPeer.getSettings();
            projectGenerator.generateProject(project, baseDir, settings, null);
        }
    }
}
