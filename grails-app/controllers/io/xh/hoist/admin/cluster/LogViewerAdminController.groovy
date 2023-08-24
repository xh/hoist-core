/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.configuration.LogbackConfig
import groovy.io.FileType
import io.xh.hoist.security.Access

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class LogViewerAdminController extends BaseClusterController {

    def listFiles() {
        runOnMember(new ListFiles())
    }
    static class ListFiles extends ClusterTask {
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


    def getFile(String filename, Integer startLine, Integer maxLines, String pattern, Boolean caseSensitive) {
        runOnMember(new GetFile(filename: filename, startLine: startLine, maxLines: maxLines, pattern: pattern, caseSensitive: caseSensitive))
    }
    static class GetFile extends ClusterTask {
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

    def download(String filename) {
        String instance = params.instance
        def task = new Download(filename: filename)
        File file = instance == clusterService.instanceName ?
            task.call() :
            clusterService.submitToMember(task, instance).get()
        render(
            file: file,
            fileName: filename,
            contentType: 'application/octet-stream'
        )
    }

    static class Download implements Callable, Serializable {
        String filename

        def call() {
            if (!availableFiles[filename]) throwUnavailable()
            return appContext.logReaderService.get(filename)
        }
    }


    /**
     * Deletes one or more files from the log directory.
     * @param filenames - (required)
     */
    @Access(['HOIST_ADMIN'])
    def deleteFiles() {
        runOnMember(new DeleteFiles(filenames: params.list('filenames')))
    }
    static class DeleteFiles extends ClusterTask {
        List filenames
        def doCall() {
            def available = availableFiles

            filenames.each { String filename ->
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
    def archiveLogs(Integer daysThreshold) {
        runOnMember(new ArchiveLogs(daysThreshold: daysThreshold))
    }
    static class ArchiveLogs extends ClusterTask {
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

    static void throwUnavailable() {
        throw new RuntimeException('Filename not valid or available')
    }
}