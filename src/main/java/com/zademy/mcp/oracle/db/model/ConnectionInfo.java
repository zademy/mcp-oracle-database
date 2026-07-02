package com.zademy.mcp.oracle.db.model;

/**
 * Lightweight database connectivity and identity information.
 *
 * @param databaseVersion banner from {@code SELECT banner FROM v$version}
 * @param databaseName    name of the database
 * @param currentUser     the Oracle user the server connects as
 * @param currentSchema   the current schema
 * @param instanceName    the instance name
 */
public record ConnectionInfo(
		String databaseVersion,
		String databaseName,
		String currentUser,
		String currentSchema,
		String instanceName) {
}
