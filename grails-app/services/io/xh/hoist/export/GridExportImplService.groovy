/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.export

import io.xh.hoist.BaseService
import io.xh.hoist.json.JSON
import io.xh.hoist.util.Utils
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFTable
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.openxmlformats.schemas.spreadsheetml.x2006.main.*

class GridExportImplService extends BaseService {

    /**
     * Return map suitable for rendering file with grails controller render() method
     */
    Map getBytesForRender(String filename, String filetype, String rows, String meta) {
        return [
            file:           getFileData(filetype, JSON.parse(rows) as List, JSON.parse(meta) as List),
            contentType:    getContentType(filetype),
            fileName:       getFileName(filename, filetype)
        ]
    }

    
    //------------------------
    // Implementation
    //------------------------
    private byte[] getFileData(String filetype, List rows, List meta) {
        switch(filetype) {
            case 'excel':
                return renderExcelFile(rows, meta, false)
            case 'excelTable':
                return renderExcelFile(rows, meta, true)
            case 'csv':
                return renderCSVFile(rows)
            default:
                throw new RuntimeException('File type not supported: ' + filetype)
        }
    }

    private String getContentType(String filetype) {
        switch(filetype) {
            case ['excel', 'excelTable']:
                return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
            case 'csv':
                return 'text/csv'
            default:
                throw new RuntimeException('File type not supported: ' + filetype)
        }
    }

    private String getFileName(String filename, String filetype) {
        def extension
        switch(filetype) {
            case ['excel', 'excelTable']:
                extension = '.xlsx'; break;
            case 'csv':
                extension = '.csv'; break;
            default:
                throw new RuntimeException('File type not supported: ' + filetype)
        }
        return filename.endsWith(extension) ? filename : "${filename}${extension}"
    }

    private byte[] renderExcelFile(List rows, List meta, Boolean asTable) {
        def tableRows = rows.size()
        def tableColumns = rows[0]['data'].size()
        Workbook wb = new XSSFWorkbook()
        Sheet sheet = wb.createSheet('Export')

        if (asTable) {
            // Create table
            XSSFTable xssfTable = sheet.createTable()
            AreaReference tableRange = new AreaReference(new CellReference(0, 0), new CellReference(tableRows - 1, tableColumns - 1))
            CTTable table = xssfTable.getCTTable()
            table.setRef(tableRange.formatAsString())
            table.setDisplayName('Export')
            table.setName('Export')
            table.setId(1L)

            // Style table
            CTTableStyleInfo tableStyle = table.addNewTableStyleInfo()
            tableStyle.setName('TableStyleMedium2')
            tableStyle.setShowColumnStripes(false)
            tableStyle.setShowRowStripes(true)

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

        // Create map of cell styles by format
        def styles = meta.collect{it.format}.unique().collectEntries {f ->
            CellStyle style = wb.createCellStyle()
            style.setDataFormat(wb.createDataFormat().getFormat(f))
            [(f): style]
        }

        def pendingGroups = [],
            completedGroups = []

        // Add rows
        rows.eachWithIndex { rowMap, i ->
            // 1) Process data for this row into cells
            Row row = sheet.createRow(i)
            List cells = Utils.stripJsonNulls(rowMap.data as List)
            cells.eachWithIndex { data, colIndex ->
                def value = data?.toString()
                Map metadata = meta[colIndex]
                Cell cell = row.createCell(colIndex)

                if (i == 0 || !value) {
                    // Column headers and empty values ignore metadata
                    cell.setCellValue(value)
                } else {
                    // Set cell value from type
                    if (metadata.type == 'date') {
                        value = Date.parse('yyyy-MM-dd', value)
                    } else if (metadata.type == 'datetime') {
                        value = Date.parse('yyyy-MM-dd HH:mm:ss', value)
                    } else if (metadata.type == 'int' || (!metadata.type && value.isInteger())) {
                        value = value.toInteger()
                    } else if (metadata.type == 'double' || (!metadata.type && value.isDouble())) {
                        value = value.toDouble()
                    }
                    cell.setCellValue(value)

                    // Set cell data format
                    if (metadata.format) {
                        cell.setCellStyle(styles[metadata.format])
                    }
                }
            }

            // 2) Create groups for Excel tree affordance
            int depth = rowMap.depth
            int prevDepth = i == 0 ? 0 : rows[i - 1].depth
            int nextDepth = i == rows.size() - 1 ? 0 : rows[i + 1].depth

            if (depth > prevDepth) {
                // Open new group
                pendingGroups.push([start: i, depth: depth])
            }

            while (pendingGroups) {
                // Close any completed groups
                def group = pendingGroups.last()
                if (group.depth <= nextDepth) break;
                group.end = i
                completedGroups << pendingGroups.pop()
            }
        }

        if (asTable && completedGroups.size()) {
            // Use the top row of the group as the summary when collapsed (i.e. same as TreePanel)
            sheet.setRowSumsBelow(false)
            // Add groups to the sheet
            completedGroups.each { sheet.groupRow(it.start, it.end) }
        }

        // Auto-width columns to fit content
        for (def i = 0; i < rows.size(); i++) {
            sheet.autoSizeColumn(i)
        }

        // Freeze top row
        sheet.createFreezePane(0, 1)

        // Get byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        wb.write(outputStream)
        outputStream.close()
        return outputStream.toByteArray()
    }

    private byte[] renderCSVFile(List rows) {
        rows = rows.collectNested { it != null && it.data != null ? it.data.toList() : '' }
        File temp = File.createTempFile('temp', '.csv')
        temp.withWriter {out ->
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

}
