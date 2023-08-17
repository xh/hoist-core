/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.configuration.LogbackConfig
import groovy.io.FileType
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class LogViewerAdminController extends BaseController {

    def logArchiveService,
        logReaderService

    def listFiles() {
        def logRootPath = logReaderService.logDir.absolutePath,
            files = availableFiles.collect {
            [
                filename    : it.key,
                size        : it.value.size(),
                lastModified: it.value.lastModified()
            ]
        }
        renderJSON(files: files, logRootPath: logRootPath)
    }

    def getFile(String filename, Integer startLine, Integer maxLines, String pattern, Boolean caseSensitive) {
        if (!availableFiles[filename]) throwUnavailable()

        // Catch any exceptions and render clean failure - the admin client auto-polls for log file
        // updates, and we don't want to spam the logs with a repeated stacktrace.
        try {
            def content = logReaderService.readFile(filename, startLine, maxLines, pattern, caseSensitive)
            renderJSON(success: true, filename: filename, content: content)
        } catch (Exception e) {
            renderJSON(success: false, filename: filename, content: [], exception: e.message)
        }
    }

    def download(String filename) {
        if (!availableFiles[filename]) throwUnavailable()
        def file = logReaderService.get(filename)
        render(
                file: file,
                fileName: filename,
                contentType: 'application/octet-stream'
        )
    }

    /**
     * Deletes one or more files from the log directory.
     * @param filenames - (required)
     */
    @Access(['HOIST_ADMIN'])
    def deleteFiles() {
        def filenames = params.list('filenames'),
            available = availableFiles

        filenames.each {String filename ->
            def toDelete = available[filename]
            if (!toDelete) throwUnavailable()

            def deleted = toDelete.delete()
            if (!deleted) logWarn("Failed to delete log: '$filename'.")
        }

        renderJSON(success:true)
    }

    /**
     * Run log archiving process immediately.
     * @param daysThreshold - (optional) min age in days of files to archive - null to use configured default.
     */
    @Access(['HOIST_ADMIN'])
    def archiveLogs(Integer daysThreshold) {
        def ret = logArchiveService.archiveLogs(daysThreshold)
        renderJSON([archived: ret])
    }


    //----------------
    // Implementation
    //----------------
    private Map<String, File> getAvailableFiles() {
        def baseDir = new File(LogbackConfig.logRootPath),
            basePath = baseDir.toPath(),
            files = []

        baseDir.eachFileRecurse(FileType.FILES) {
            def matches = it.name ==~ /.*\.log/
            if (matches) files << it
        }

        files.collectEntries { File f ->
            [basePath.relativize(f.toPath()).toString(), f]
        }
    }

    private static throwUnavailable() {
        throw new RuntimeException('Filename not valid or available')
    }
}
