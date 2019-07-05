/*
 * Copyright 2018 wanghonglin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wanghonglin.xjavadoc;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.wanghonglin.xjavadoc.gles.GLESJavadoc;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class ExtraJavadocAction extends AnAction {

    public ExtraJavadocAction() {
        super("Apply extra javadoc...");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getProject();

        if (project != null) {

            final List<GLESJavadoc> javadocList = Arrays.asList(new GLESJavadoc(project, "GLES"));
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "GENERATING...") {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(false);
                    int currentFinishCount = 0;
                    for (GLESJavadoc javadoc : javadocList) {
                        currentFinishCount++;
                        indicator.setText("Generating extra javadoc for " + javadoc.getName());
                        indicator.setFraction(((double) currentFinishCount) / javadocList.size());
                        javadoc.applyJavadoc();
                    }

                    indicator.setText("Done...");
                    indicator.setFraction(1.0);
                    Logger.getInstance(ExtraJavadocAction.class).info("DONE");

                    ApplicationManagerEx.getApplicationEx().invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            tryToRestartIDE();
                        }
                    });
                }
            });
        }
    }

    private void tryToRestartIDE() {
        final int okCancel = Messages.showOkCancelDialog("Restart to apply changes?", "Restart " +
                        ApplicationInfoEx.getInstanceEx().getVersionName(),
                Messages.getInformationIcon());
        if (okCancel == Messages.OK) {
            ApplicationManagerEx.getApplicationEx().restart(true);
        }
    }
}
