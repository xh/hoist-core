/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.export

import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import spock.lang.Specification

/**
 * Tests {@link GridExportImplService} by reading its Excel output back with POI and its CSV
 * output as text. Rows follow the hoist-react export payload: the first row holds the headers,
 * each row has `data` and `depth`, and `meta` describes each column.
 */
class GridExportImplServiceSpec extends Specification implements ServiceUnitTest<GridExportImplService>, HoistUnitTest {

    static final List META = [
        [type: 'string'],
        [type: 'int', format: '#,##0', width: 20],
        [type: 'number', format: '0.00'],
        [type: 'bool'],
        [type: 'date', format: 'yyyy-mm-dd hh:mm'],
        [type: 'localDate', format: 'yyyy-mm-dd'],
    ]

    static final List ROWS = [
        [data: ['Name', 'Qty', 'Price', 'Active', 'Updated', 'Day'], depth: 0],
        [data: ['Widget', '5', '1.5', 'true', '2026-01-02 03:04:05', '2026-01-02'], depth: 0],
        [data: ['Gadget', '', 'x', null, 'not a date', ''], depth: 0],
    ]

    def setup() {
        testConfigService.registerTypedConfig('xhExportConfig', ExportConfig)
    }

    //-------------------
    // Excel
    //-------------------
    def 'excel export writes headers and typed cells, and reads back with POI'() {
        when:
        def ret = service.getBytesForRender(type: 'excel', filename: 'report', rows: ROWS, meta: META)
        def sheet = sheetOf(ret.file)

        then:
        ret.contentType == 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        ret.fileName == 'report.xlsx'
        sheet.sheetName == 'Export'
        (0..5).collect { sheet.getRow(0).getCell(it).stringCellValue } == ROWS[0].data

        and: 'declared types drive the cell type'
        with(sheet.getRow(1)) {
            getCell(0).stringCellValue == 'Widget'
            getCell(1).cellType == CellType.NUMERIC
            getCell(1).numericCellValue == 5d
            getCell(2).numericCellValue == 1.5d
            getCell(3).cellType == CellType.BOOLEAN
            getCell(3).booleanCellValue
            getCell(4).cellType == CellType.NUMERIC
            getCell(4).dateCellValue.format('yyyy-MM-dd HH:mm:ss') == '2026-01-02 03:04:05'
            getCell(5).localDateTimeCellValue.toLocalDate().toString() == '2026-01-02'
        }

        and: 'formats are applied to data cells only'
        sheet.getRow(1).getCell(1).cellStyle.dataFormatString == '#,##0'
        sheet.getRow(1).getCell(2).cellStyle.dataFormatString == '0.00'
        sheet.getRow(0).getCell(1).cellStyle.dataFormatString == 'General'

        and: 'a declared width is honored, the top row is frozen, and no table is created'
        sheet.getColumnWidth(1) == 20 * 256
        sheet.paneInformation.horizontalSplitPosition == 1
        sheet.tables.isEmpty()
    }

    def 'values that do not parse for their type are written as text, and empty values carry no type'() {
        when:
        def sheet = sheetOf(service.getBytesForRender(type: 'excel', filename: 'r', rows: ROWS, meta: META).file)

        then:
        with(sheet.getRow(2)) {
            getCell(1).cellType == CellType.STRING
            getCell(1).stringCellValue == ''
            getCell(2).stringCellValue == 'x'
            getCell(3).cellType == CellType.BLANK
            getCell(4).stringCellValue == 'not a date'
        }
    }

    def 'per-cell maps override the column type and format'() {
        given:
        def rows = [
            [data: ['Mixed'], depth: 0],
            [data: [[value: '7', type: 'int', format: '0']], depth: 0],
            [data: [[value: 'seven']], depth: 0],
        ]

        when:
        def sheet = sheetOf(service.getBytesForRender(type: 'excel', filename: 'r', rows: rows, meta: [[type: 'string']]).file)

        then:
        sheet.getRow(1).getCell(0).numericCellValue == 7d
        sheet.getRow(1).getCell(0).cellStyle.dataFormatString == '0'
        sheet.getRow(2).getCell(0).stringCellValue == 'seven'
    }

    def 'excelTable export adds a filtered table and groups nested rows'() {
        given:
        def rows = [
            [data: ['Region', 'Sales'], depth: 0],
            [data: ['East', '10'], depth: 0],
            [data: ['NY', '6'], depth: 1],
            [data: ['Albany', '2'], depth: 2],
            [data: ['Boston', '4'], depth: 1],
            [data: ['West', '3'], depth: 0],
        ]

        when:
        def sheet = sheetOf(service.getBytesForRender(type: 'excelTable', filename: 'r.xlsx', rows: rows, meta: [[type: 'string'], [type: 'int']]).file)

        then:
        sheet.tables.size() == 1
        with(sheet.tables[0]) {
            name == 'ExportTable'
            CTTable.tableColumns.tableColumnList*.name == ['Region', 'Sales']
            CTTable.autoFilter.filterColumnList.size() == 2
            CTTable.tableStyleInfo.showRowStripes == false
        }

        and: 'children are outlined beneath their parent, with the parent row as the summary'
        (1..5).collect { sheet.getRow(it).CTRow.outlineLevel } == [0, 1, 2, 1, 0]
        !sheet.rowSumsBelow
    }

    def 'an export over the streaming threshold uses the streaming writer without table formatting'() {
        given:
        testConfigService.set('xhExportConfig', [streamingCellThreshold: 2])

        when:
        def sheet = sheetOf(service.getBytesForRender(type: 'excelTable', filename: 'r', rows: ROWS, meta: META).file)

        then:
        sheet.tables.isEmpty()
        sheet.getRow(1).getCell(0).stringCellValue == 'Widget'
        sheet.getRow(1).getCell(1).numericCellValue == 5d
    }

    //-------------------
    // CSV
    //-------------------
    def 'csv export quotes every value and doubles embedded quotes'() {
        given:
        def rows = [
            [data: ['Name', 'Note'], depth: 0],
            [data: ['Widget', 'Say "hi"'], depth: 0],
            [data: ['Gadget', ''], depth: 0],
        ]

        when:
        def ret = service.getBytesForRender(type: 'csv', filename: 'report.csv', rows: rows, meta: [[:], [:]])

        then:
        ret.contentType == 'text/csv'
        ret.fileName == 'report.csv'
        new String(ret.file, 'UTF-8').readLines() == ['"Name","Note"', '"Widget","Say ""hi"""', '"Gadget",""']
    }

    //-------------------
    // Misc
    //-------------------
    def 'an unsupported export type throws'() {
        when:
        service.getBytesForRender(type: 'pdf', filename: 'r', rows: ROWS, meta: META)

        then:
        def e = thrown(RuntimeException)
        e.message.contains('not supported')
    }

    //-------------------
    // Helpers
    //-------------------
    private static XSSFSheet sheetOf(byte[] bytes) {
        new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheet('Export')
    }
}
