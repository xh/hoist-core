/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import groovy.io.FileType
import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.cluster.ClusterJsonRequest
import io.xh.hoist.configuration.LogbackConfig
import io.xh.hoist.security.Access
import io.xh.hoist.exception.RoutineRuntimeException

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class LogViewerAdminController extends BaseController {

    def listFiles(String instance) {
        runOnInstance(new ListFiles(), instance)
    }

    static class ListFiles extends ClusterJsonRequest {
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

    static class GetFile extends ClusterJsonRequest {
        String filename
        Integer startLine
        Integer maxLines
        String pattern
        Boolean caseSensitive

        def doCall() {
            if (!availableFiles[filename]) throwUnavailable(filename)
            try {
                def content = appContext.logReaderService.readFile(filename, startLine, maxLines, pattern, caseSensitive)
                return [success: true, filename: filename, content: content]
            } catch (FileNotFoundException ignored) {
                throwUnavailable(filename)
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

        render(ret)
    }

    static class Download extends ClusterRequest<Map> {
        String filename
        Map doCall() {
            if (!availableFiles[filename]) throwUnavailable(filename)
            def file = appContext.logReaderService.get(filename)
            [
                file: file.bytes,
                fileName: filename,
                contentType: 'application/octet-stream'
            ]
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

    static class DeleteFiles extends ClusterJsonRequest {
        List<String> filenames

        def doCall() {
            def available = availableFiles

            filenames.each { filename ->
                def toDelete = available[filename]
                if (!toDelete) throwUnavailable(filename)

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

    static class ArchiveLogs extends ClusterJsonRequest {
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

    static void throwUnavailable(String filename) {
        throw new RoutineRuntimeException("Filename not valid or available: $filename")
    }
}
