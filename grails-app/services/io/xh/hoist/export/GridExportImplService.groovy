/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.export

import io.xh.hoist.BaseService
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFTable
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.openxmlformats.schemas.spreadsheetml.x2006.main.*

import java.awt.Color
import java.awt.GraphicsEnvironment
import java.time.LocalDate

/**
 * Service to export row data to Excel or CSV.
 *
 * Uses the following properties from `xhExportConfig`:
 *
 *      `streamingCellThreshold`:   Maximum cell count to export to Excel as fully formatted table. Exports with cell
 *                                  counts that exceed this value this will use Apache POI's streaming API, which frees
 *                                  up heap space at the expenses of removing the table formatting.
 *                                  See https://poi.apache.org/components/spreadsheet/how-to.html#sxssf
 */
class GridExportImplService extends BaseService {

    def configService

    private Date lastExportDate = null
    private Long exportCount = 0

    void init() {
        if (instanceLog.debugEnabled) {
            GraphicsEnvironment ge = GraphicsEnvironment.localGraphicsEnvironment
            logDebug(
                'Fonts available for Excel Column Sizing',
                ge.allFonts*.name.toString()
            )
        }
    }

    /**
     * Return map suitable for rendering file with grails controller render() method
     */
    Map getBytesForRender(Map params) {
        withDebug([
            _msg: 'Generating Export',
            _filename: params.filename,
            rows: params.rows.size(),
            cols: params.meta.size()
        ]) {
            def ret =  [
                    file       : getFileData(params.type, params.rows, params.meta),
                    contentType: getContentType(params.type),
                    fileName   : getFileName(params.filename, params.type)
            ]

            lastExportDate = new Date()
            exportCount++;
            return ret

        }
    }


    //------------------------
    // Implementation
    //------------------------
    // FieldType from Field.js in Hoist-React
    private final static def FieldType = [AUTO: 'auto', BOOL: 'bool', DATE: 'date', INT: 'int', LOCAL_DATE: 'localDate', NUMBER: 'number', STRING: 'string'].asImmutable()
    // ExcelFormat from ExcelFormat.js in Hoist-React
    private final static def ExcelFormat = [LONG_TEXT: 'Text'].asImmutable()

    private byte[] getFileData(String type, List rows, List meta) {
        switch(type) {
            case 'excel':
                return renderExcelFile(rows, meta, false)
            case 'excelTable':
                return renderExcelFile(rows, meta, true)
            case 'csv':
                return renderCSVFile(rows)
            default:
                throw new RuntimeException('Export type not supported: ' + type)
        }
    }

    private String getContentType(String type) {
        switch(type) {
            case ['excel', 'excelTable']:
                return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
            case 'csv':
                return 'text/csv'
            default:
                throw new RuntimeException('Export type not supported: ' + type)
        }
    }

    private String getFileName(String filename, String type) {
        def extension
        switch(type) {
            case ['excel', 'excelTable']:
                extension = '.xlsx'
                break
            case 'csv':
                extension = '.csv'
                break
            default:
                throw new RuntimeException('Export type not supported: ' + type)
        }
        return filename.endsWith(extension) ? filename : "${filename}${extension}"
    }

    private byte[] renderExcelFile(List rows, List meta, Boolean asTable) {
        def tableRows = rows.size()
        def tableColumns = rows[0]['data'].size()
        def tableCells = tableRows * tableColumns
        def useStreamingAPI = tableCells > config.streamingCellThreshold
        def maxDepth = rows.collect{it.depth}.max()
        def grouped = maxDepth > 0
        def wb

        if (useStreamingAPI) {
            wb = new SXSSFWorkbook(100)
            asTable = false
        } else {
            wb = new XSSFWorkbook()
        }

        Sheet sheet = wb.createSheet('Export')
        if (asTable) {
            // Create table
            XSSFTable xssfTable = sheet.createTable()
            AreaReference tableRange = new AreaReference(new CellReference(0, 0), new CellReference(Math.max(1, tableRows - 1), tableColumns - 1), SpreadsheetVersion.EXCEL2007)
            CTTable table = xssfTable.getCTTable()
            table.setRef(tableRange.formatAsString())
            table.setDisplayName('Export')
            table.setName('Export')
            table.setId(1L)

            // Style table
            CTTableStyleInfo tableStyle = table.addNewTableStyleInfo()
            tableStyle.setName('TableStyleMedium2')
            tableStyle.setShowColumnStripes(false)
            tableStyle.setShowRowStripes(!grouped)

            // Create sortable header columns
            CTTableColumns columns = table.addNewTableColumns()
            CTAutoFilter autofilter = table.addNewAutoFilter()
            columns.setCount(tableColumns as Long)

            // First row's cells are column headers
            rows[0]['data'].eachWithIndex { name, index ->
                def colId = index + 1

                CTTableColumn column = columns.addNewTableColumn()
                column.setName(name.toString())
                column.setId(colId)

                CTFilterColumn filter = autofilter.addNewFilterColumn()
                filter.setColId(colId)
                filter.setShowButton(true)
            }
        }

        def definedWidthColumns = []
        meta.eachWithIndex{col, i -> if (col.width) {
            definedWidthColumns.add(i)
        }}

        def styles = [:],
            groupColors = [],
            pendingGroups = [],
            completedGroups = [],
            valueParseFailures = 0

        if (grouped) {
            def startColor = new Color(181, 198, 235)
            def endColor = new Color(255, 255, 255)
            for (def i = 0; i <= maxDepth; i++) {
                groupColors.add(blendColors(startColor, endColor, i / maxDepth))
            }
        }

        // Add rows
        rows.eachWithIndex { rowMap, i ->
            // 1) Process data for this row into cells
            Row row = sheet.createRow(i)
            List cells = rowMap.data as List
            cells.eachWithIndex { data, colIndex ->
                Map metadata = meta[colIndex]
                Cell cell = row.createCell(colIndex)

                // Collect cell value, cell format, and cell type
                def value, format, type
                if (data instanceof Map) {
                    value = data?.value
                    format = data?.format ?: metadata.format
                    type = data?.type ?: metadata.type
                } else {
                    value = data
                    format = metadata.format
                    type = metadata.type
                }

                value = value?.toString()

                // Set cell data format (skipping column headers)
                // Cache style based on format and depth (for group colors) in order to prevent costly or prohibited
                // generation of cell styles on workbook (max. 64,000)
                if (i > 0 && format) {
                    int depth = rowMap.depth ?: 0
                    String styleKey = format + '|' + depth.toString()
                    if (!styles[styleKey]) {
                        styles[styleKey] = registerCellStyleForFormat(wb, format, grouped ? groupColors[depth] : null)
                    }
                    cell.setCellStyle(styles[styleKey])
                }

                if (i == 0 || !value) {
                    // Column headers and empty values ignore metadata
                    cell.setCellValue(value)
                } else {
                    // Set cell value using type (FieldType from Field.js), otherwise default to text
                    try {
                        if (type == FieldType.DATE) {
                            // Note that the format string for SimpleDateFormat (called here using Groovy's
                            // DateUtilStaticExtension) is slightly different from js and excel date formatting
                            value = Date.parse('yyyy-MM-dd HH:mm:ss', value)
                        } else if (type == FieldType.LOCAL_DATE) {
                            // Defaults to ISO local date format, which is 'yyyy-MM-dd'
                            value = LocalDate.parse(value)
                        } else if (type == FieldType.INT) {
                            value = value.toLong()
                        } else if (type == FieldType.NUMBER) {
                            value = value.toDouble()
                        } else if (type == FieldType.BOOL) {
                            value = value.toBoolean()
                        }
                    } catch (Exception ex) {
                        if (valueParseFailures < 100) {
                            logTrace("Error parsing value $value for declared type ${type} and format ${format}", ex.message)
                        }
                        valueParseFailures++
                    }
                    cell.setCellValue(value)
                }

            }

            // 2) Create groups for Excel tree affordance
            int depth = rowMap.depth
            int prevDepth = i == 0 ? 0 : rows[i - 1].depth
            int nextDepth = i == rows.size() - 1 ? 0 : rows[i + 1].depth

            if (depth > prevDepth) {
                // Open new group
                pendingGroups.add([start: i, depth: depth])
            }

            while (pendingGroups) {
                // Close any completed groups
                def group = pendingGroups.last()
                if (group.depth <= nextDepth) break
                group.end = i
                completedGroups << pendingGroups.removeLast()
            }
        }

        if (valueParseFailures) {
            logWarn("Errors encountered during parsing for grid export - failed to parse $valueParseFailures cell values.")
        }

        if (asTable && completedGroups.size()) {
            // Use the top row of the group as the summary when collapsed (i.e. same as TreePanel)
            sheet.setRowSumsBelow(false)
            // Add groups to the sheet
            completedGroups.each { sheet.groupRow(it.start, it.end) }
        }

        // Auto-width columns to fit content
        if (!useStreamingAPI) {
            for (int i = 0; i < tableColumns; i++) {
                if (definedWidthColumns.contains(i)) {
                    int colWidth = meta.get(i).width * 256
                    sheet.setColumnWidth(i, colWidth)
                } else {
                    sheet.autoSizeColumn(i)
                }
            }
        }

        // Freeze top row
        sheet.createFreezePane(0, 1)

        // Get byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        wb.write(outputStream)
        outputStream.close()
        if (useStreamingAPI) wb.dispose()
        return outputStream.toByteArray()
    }

    private XSSFCellStyle registerCellStyleForFormat(wb, format, colorGroup) {
        XSSFCellStyle style = wb.createCellStyle()
        if (format == ExcelFormat.LONG_TEXT) style.setWrapText(true)
        style.setVerticalAlignment(VerticalAlignment.CENTER)
        style.setDataFormat(wb.createDataFormat().getFormat(format.toString()))

        // If rendering grouped data into a table, set background color based on depth.
        // Note the confusing use of `ForegroundColor` to set background color below. This is because
        // Excel thinks of background colors as patterned "Fills", which each have their own
        // background and foreground colors. A solid background is `FillPatternType.SOLID_FOREGROUND`.
        if (colorGroup) {
            style.setFillForegroundColor(new XSSFColor(colorGroup));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        return style
    }

    private Color blendColors(Color c1, Color c2, ratio) {
        if (ratio <= 0) return c1
        if (ratio >= 1) return c2

        def iRatio = 1 - ratio;
        int r = (c1.red * iRatio) + (c2.red * ratio)
        int g = (c1.green * iRatio) + (c2.green * ratio)
        int b = (c1.blue * iRatio) + (c2.blue * ratio)

        return new Color(r, g, b)
    }

    private byte[] renderCSVFile(List rows) {
        rows = rows.collectNested { it != null && it.data != null ? it.data.toList() : '' }
        File temp = File.createTempFile('temp', '.csv')
        temp.withWriter('UTF-8') {out ->
            rows.each { row ->
                // Replace double quotes as a pair of double quotes ("") - this to allow
                // proper parsing by Excel back into a single double quote
                row = row.collect { it ? '"' + it.replace('"', '""') + '"' : '""' }
                out.writeLine(row.join(','))
            }
        }

        byte[] ret = temp.getBytes()
        temp.delete()

        return ret
    }

    private Map getConfig() {
        configService.getMap('xhExportConfig')
    }


    Map getAdminStats() {
        return [
            config: configForAdminStats('xhExportConfig'),
            exportCount: exportCount,
            lastExportDate: lastExportDate
        ]
    }

}
