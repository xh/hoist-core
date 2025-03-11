/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster


import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import static io.xh.hoist.util.ClusterUtils.runOnInstance

@Access(['HOIST_ADMIN_READER'])
class LogViewerAdminController extends BaseController {

    def logReaderService
    def logArchiveService

    def listFiles(String instance) {
        def ret = runOnInstance(logReaderService.&listFiles, instance: instance, asJson: true)
        renderClusterJSON(ret)
    }

    def getFile(
        String filename,
        Integer startLine,
        Integer maxLines,
        String pattern,
        Boolean caseSensitive,
        String instance
    ) {
        def ret = runOnInstance(
            logReaderService.&getFile,
            args: [filename, startLine, maxLines, pattern, caseSensitive],
            instance: instance,
            asJson: true
        )
        renderClusterJSON(ret)
    }

    def download(String filename, String instance) {
        def ret = runOnInstance(logReaderService.&get, args: [filename], instance: instance)

        if (ret.exception) {
            // Just render exception, was already logged on target instance
            xhExceptionHandler.handleException(exception: ret.exception, renderTo: response)
            return
        }
        def file = ret.value as File
        render(
            file: file.bytes,
            fileName: filename,
            contentType: 'application/octet-stream'
        )
    }


    /**
     * Deletes one or more files from the log directory.
     * @param filenames - (required)
     */
    @Access(['HOIST_ADMIN'])
    def deleteFiles(String instance) {
        def ret = runOnInstance(
            logReaderService.&deleteFiles,
            args: [params.list('filenames')],
            instance: instance,
            asJson: true
        )
        renderClusterJSON(ret)
    }

    /**
     * Run log archiving process immediately.
     * @param daysThreshold - (optional) min age in days of files to archive - null to use configured default.
     */
    @Access(['HOIST_ADMIN'])
    def archiveLogs(Integer daysThreshold, String instance) {
        def ret = runOnInstance(
            logArchiveService.&archiveLogs,
            args: [daysThreshold],
            instance: instance,
            asJson: true
        )
        renderClusterJSON(ret)
    }
}
