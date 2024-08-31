/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import groovy.io.FileType
import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.configuration.LogbackConfig
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class LogViewerAdminController extends BaseController {

    def listFiles(String instance) {
        runOnInstance(new ListFiles(), instance)
    }

    static class ListFiles extends ClusterRequest {
        def doCall() {
            def logRootPath = appContext.logReaderService.logDir.absolutePath,
                files = availableFiles.collect {
                    [
                        filename    : it.key,
                        size        : it.value.size(),
                        lastModified: it.value.lastModified()
                    ]
                }
            return [files: files, logRootPath: logRootPath]
        }
    }


    def getFile(
        String filename,
        Integer startLine,
        Integer maxLines,
        String pattern,
        Boolean caseSensitive,
        String instance
    ) {
        runOnInstance(
            new GetFile(
                filename: filename,
                startLine: startLine,
                maxLines: maxLines,
                pattern: pattern,
                caseSensitive: caseSensitive
            ),
            instance
        )
    }

    static class GetFile extends ClusterRequest {
        String filename
        Integer startLine
        Integer maxLines
        String pattern
        Boolean caseSensitive

        def doCall() {
            if (!availableFiles[filename]) throwUnavailable()

            // Catch any exceptions and render clean failure - the admin client auto-polls for log file
            // updates, and we don't want to spam the logs with a repeated stacktrace.
            try {
                def content = appContext.logReaderService.readFile(filename, startLine, maxLines, pattern, caseSensitive)
                return [success: true, filename: filename, content: content]
            } catch (Exception e) {
                return [success: false, filename: filename, content: [], exception: e.message]
            }
        }
    }

    def download(String filename, String instance) {
        def task = new Download(filename: filename),
            ret = clusterService.submitToInstance(task, instance)

        if (ret.exception) {
            // Just render exception, was already logged on target instance
            xhExceptionHandler.handleException(exception: ret.exception, renderTo: response)
            return
        }

        render(
            file: ret.value,
            fileName: filename,
            contentType: 'application/octet-stream'
        )
    }

    static class Download extends ClusterRequest<File> {
        String filename

        File doCall() {
            if (!availableFiles[filename]) throwUnavailable()
            return appContext.logReaderService.get(filename)
        }
    }


    /**
     * Deletes one or more files from the log directory.
     * @param filenames - (required)
     */
    @Access(['HOIST_ADMIN'])
    def deleteFiles(String instance) {
        runOnInstance(new DeleteFiles(filenames: params.list('filenames')), instance)
    }

    static class DeleteFiles extends ClusterRequest {
        List<String> filenames

        def doCall() {
            def available = availableFiles

            filenames.each { filename ->
                def toDelete = available[filename]
                if (!toDelete) throwUnavailable()

                def deleted = toDelete.delete()
                if (!deleted) logWarn("Failed to delete log: '$filename'.")
            }
            [success: true]
        }
    }

    /**
     * Run log archiving process immediately.
     * @param daysThreshold - (optional) min age in days of files to archive - null to use configured default.
     */
    @Access(['HOIST_ADMIN'])
    def archiveLogs(Integer daysThreshold, String instance) {
        runOnInstance(new ArchiveLogs(daysThreshold: daysThreshold), instance)
    }

    static class ArchiveLogs extends ClusterRequest {
        Integer daysThreshold

        def doCall() {
            def ret = appContext.logArchiveService.archiveLogs(daysThreshold)
            return [archived: ret]
        }
    }

    //----------------
    // Implementation
    //----------------
    static Map<String, File> getAvailableFiles() {
        def baseDir = new File(LogbackConfig.logRootPath),
            basePath = baseDir.toPath()

        List<File> files = []
        baseDir.eachFileRecurse(FileType.FILES) {
            def matches = it.name ==~ /.*\.log/
            if (matches) files << it
        }

        files.collectEntries { File f ->
            [basePath.relativize(f.toPath()).toString(), f]
        }
    }

    static void throwUnavailable() {
        throw new RuntimeException('Filename not valid or available')
    }
}
