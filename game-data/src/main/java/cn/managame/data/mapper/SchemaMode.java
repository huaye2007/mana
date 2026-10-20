package cn.managame.data.mapper;

/**
 * What a backend may do to storage structure while a repository is initialized. One value is chosen for the
 * whole component through {@code GameDataBuilder.schema} and handed to every
 * {@link EntityMapper#initialize}, so the deployment decides this once instead of each backend deciding for
 * itself. A Mapper an application wrote obeys the same three meanings.
 *
 * <p>{@link #CREATE} is the default, because a first deployment has nothing to validate against. A database
 * whose structure is managed outside the application — migrations, a DBA, a read-only account — wants
 * {@link #VALIDATE}, so a mapping that no longer matches is a startup failure rather than a write failure
 * hours later, or {@link #NONE} where the application may not read catalog tables at all.
 */
public enum SchemaMode {
    /**
     * Creates what is missing and validates what already exists: a missing table, missing columns and
     * missing declared indexes are created, and everything present must be able to hold what the mapping
     * writes. Additive only — nothing is dropped, narrowed or retyped — and tolerant of another instance of
     * the same service running the same statements at the same time.
     */
    CREATE,
    /**
     * Validates and issues no DDL. A missing table, a missing column, a missing declared index or one that
     * cannot hold what the mapping writes fails the startup that found it.
     */
    VALIDATE,
    /**
     * Reads no catalog and checks nothing. Storage structure is entirely the deployment's business, and a
     * mismatch is first observed as a failing read or write.
     */
    NONE
}
