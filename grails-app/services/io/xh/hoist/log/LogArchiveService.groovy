/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.log

import groovy.io.FileType
import io.xh.hoist.BaseService

import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import static io.xh.hoist.log.LogUtils.logRootPath
import static io.xh.hoist.util.DateTimeUtils.DAYS
import static java.io.File.separator

/**
 * Support for automatic cleanup of server log files. Files older than the day limit configured
 * within the `xhLogArchiveConfig` AppConfig key will be moved into a configurable archive
 * directory and compressed into archives for each category.
 */
class LogArchiveService extends BaseService {

    def configService

    void init() {
        createTimer(interval: 1 * DAYS)
    }

    List<String> archiveLogs(Integer daysThreshold) {
        if (!config.archiveFolder) {
            log.warn("Log archiving disabled due to incomplete / disabled xhLogArchiveConfig entry")
            return []
        }

        File logPath = getLogPath()
        List archivedFilenames = []

        daysThreshold = daysThreshold ?: config.archiveAfterDays

        List<File> oldLogs = getOldLogFiles(logPath, daysThreshold)
        withInfo("Archiving ${oldLogs.size()} log(s) older than ${daysThreshold} days.") {
            Map logsByCategory = mapLogsByCategory(oldLogs)

            logsByCategory.each {String category, List<File> logFiles ->
                File archivePath = getArchivePath(logPath.absolutePath, category)
                if (!archivePath.exists()) archivePath.mkdirs()

                Map logsByMonth = mapLogsByMonth(logFiles)
                logsByMonth.each {String month, files ->
                    ZipOutputStream zipStream = getZipStream(archivePath.absolutePath, month)
                    files.each {File file ->
                        writeFileToZip(zipStream, file)
                        file.delete()
                        archivedFilenames << file.name
                    }
                    zipStream.close()
                }
            }
        }

        return archivedFilenames
    }


    //------------------------
    // Implementation
    //------------------------
    private void onTimer() {
        archiveLogs((Integer) config.archiveAfterDays)
    }

    private File getLogPath() {
        return new File(Paths.get(logRootPath).toString())
    }

    private File getArchivePath(String logPath, String category) {
        return new File(logPath + separator + config.archiveFolder + separator + category)
    }

    private Map mapLogsByCategory(List<File> files) {
        files.groupBy {
            def category = it.name =~ /((?:\w|-|_|\+)+)/
            category.find()
            return category.group(1)
        }
    }

    private Map mapLogsByMonth(List<File> files) {
        files.groupBy {
            def month = it.name =~ /(\d{4}-\d{2})/
            month.find()
            return month.group(1)
        }
    }

    private List<File> getOldLogFiles(File logPath, int daysThreshold) {
        if (daysThreshold < 0) return

        def files = []
        logPath.eachFile(FileType.FILES) {
            def matcher = it.name =~ /(?:\w|-|_|\+)+\.(\d{4}(?:-\d{2}){2})\.log$/
            if (matcher.find() && isOlderThanThreshold(matcher.group(1), daysThreshold)) {
                files.add(it)
            }
        }
        return files
    }

    private boolean isOlderThanThreshold(String date, int daysThreshold) {
        def currentDate = new Date(),
            fileDate = Date.parse('yyyy-MM-dd', date)

        use (groovy.time.TimeCategory) {
            def duration = currentDate - fileDate
            return duration.days > daysThreshold
        }
    }

    private ZipOutputStream getZipStream(String path, String month) {
        def zipName = path + separator + month + '.zip',
            zipFile = new File(zipName)

        return zipFile.exists() ? reopenExistingStream(zipFile) : new ZipOutputStream(new FileOutputStream(zipName))
    }

    private ZipOutputStream reopenExistingStream(File zipFile) throws IOException {
        File tempFile = File.createTempFile(zipFile.name, null)
        tempFile.delete()

        if (!zipFile.renameTo(tempFile)) {
            throw new RuntimeException("Could not rename file " + zipFile.absolutePath + " to " + tempFile.absolutePath)
        }

        ZipInputStream inStream = new ZipInputStream(new FileInputStream(tempFile))
        ZipOutputStream outStream = new ZipOutputStream(new FileOutputStream(zipFile))

        ZipEntry entry = inStream.getNextEntry()
        while (entry) {
            writeZipToZip(inStream, outStream, entry)
            entry = inStream.getNextEntry()
        }
        inStream.close()
        tempFile.delete()

        return outStream
    }

    private void writeFileToZip(ZipOutputStream zipStream, File file) {
        def buffer = new byte[file.size()]

        zipStream.putNextEntry(new ZipEntry(file.name))
        file.withInputStream {stream ->
            zipStream.write(buffer, 0, stream.read(buffer))
        }
        zipStream.closeEntry()
    }

    private void writeZipToZip(ZipInputStream inStream, ZipOutputStream outStream, ZipEntry file) {
        def buffer = new byte[1024],
            len

        outStream.putNextEntry(new ZipEntry(file.name))

        while ((len = inStream.read(buffer)) > 0) {
            outStream.write(buffer, 0, len)
        }
    }

    private Map getConfig() {
        return configService.getMap('xhLogArchiveConfig')
    }

}
