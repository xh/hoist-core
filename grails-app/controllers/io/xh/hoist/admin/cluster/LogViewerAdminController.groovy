/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster


import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.util.Utils
import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson
import static io.xh.hoist.util.ClusterUtils.runOnInstance


@Access(['HOIST_ADMIN_READER'])
class LogViewerAdminController extends BaseController {

    def logReaderService
    def logArchiveService

    def listFiles(String instance) {
        def ret = runOnInstanceAsJson(logReaderService.&listFiles, instance)
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
        def ret = runOnInstanceAsJson(
            logReaderService.&getFile,
            instance,
            [filename, startLine, maxLines, pattern, caseSensitive]
        )
        renderClusterJSON(ret)
    }

    def download(String filename, String instance) {
        def ret = runOnInstance(logReaderService.&get, instance, [filename])

        if (ret.exception) {
            // Just render exception, was already logged on target instance
            Utils.handleException(exception: ret.exception, renderTo: response)
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
        def ret = runOnInstanceAsJson(logReaderService.&deleteFiles, instance, [params.list('filenames')])
        renderClusterJSON(ret)
    }

    /**
     * Run log archiving process immediately.
     * @param daysThreshold - (optional) min age in days of files to archive - null to use configured default.
     */
    @Access(['HOIST_ADMIN'])
    def archiveLogs(Integer daysThreshold, String instance) {
        def ret = runOnInstanceAsJson(logArchiveService.&archiveLogs, instance, [daysThreshold])
        renderClusterJSON(ret)
    }
}
