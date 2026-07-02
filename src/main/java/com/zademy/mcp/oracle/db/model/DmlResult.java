package com.zademy.mcp.oracle.db.model;

/**
 * Result of a DML statement (INSERT / UPDATE / DELETE / MERGE).
 *
 * @param statementKind the detected statement kind (INSERT, UPDATE, ...)
 * @param rowsAffected  number of rows affected in the database
 */
public record DmlResult(String statementKind, int rowsAffected) {
}
