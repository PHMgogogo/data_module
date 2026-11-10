package com.project.phm.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvUtilsTest {

    @Test
    void normalizesCsvFileNameAndOptionalPrefix() {
        assertEquals("csv_2026_afd_data",
                CsvUtils.normalizeTableName("2026_afd_data.csv"));
        assertEquals("csv_2026_afd_data",
                CsvUtils.normalizeTableName("csv_2026_afd_data.CSV"));
        assertEquals("csv_engine",
                CsvUtils.normalizeTableName("  CSV_engine  "));
    }

    @Test
    void rejectsBlankOrUnsafeTableNames() {
        assertThrows(IllegalArgumentException.class,
                () -> CsvUtils.normalizeTableName(null));
        assertThrows(IllegalArgumentException.class,
                () -> CsvUtils.normalizeTableName(" .csv "));
        assertThrows(IllegalArgumentException.class,
                () -> CsvUtils.normalizeTableName("engine.csv.bak"));
        assertThrows(IllegalArgumentException.class,
                () -> CsvUtils.normalizeTableName("engine-data"));
    }

    @Test
    void validatesFullTableIdentifier() {
        assertTrue(CsvUtils.isValidTableName("csv_2026_afd_data"));
        assertTrue(CsvUtils.isValidTableName("csv_设备数据"));
        assertFalse(CsvUtils.isValidTableName("2026_afd_data"));
        assertFalse(CsvUtils.isValidTableName("csv_2026_afd_data.csv"));
    }
}
