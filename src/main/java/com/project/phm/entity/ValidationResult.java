package com.project.phm.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据校验结果
 */
public class ValidationResult {
    private boolean valid;
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private List<String> columns;
    private List<String> warnings;
    private List<String> errors;
    private List<String[]> sampleData;

    public ValidationResult() {
        this.valid = true;
        this.totalRows = 0;
        this.validRows = 0;
        this.invalidRows = 0;
        this.columns = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.errors = new ArrayList<>();
        this.sampleData = new ArrayList<>();
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public void addError(String error) {
        this.errors.add(error);
        this.valid = false;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public void setValidRows(int validRows) {
        this.validRows = validRows;
    }

    public int getInvalidRows() {
        return invalidRows;
    }

    public void setInvalidRows(int invalidRows) {
        this.invalidRows = invalidRows;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public List<String[]> getSampleData() {
        return sampleData;
    }

    public void setSampleData(List<String[]> sampleData) {
        this.sampleData = sampleData;
    }
}
