package com.amazon.corretto.gradle.plugin.customtar

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.Project
import org.gradle.api.Plugin

class CustomTarPlugin implements Plugin<Project> {
    protected final Logger log = Logging.getLogger(getClass())
    void apply(Project project) {
        project.tasks.register('TarWithSymlinks', TarWithSymlinks.class) {
        }
    }
}
