package za.co.capitec.sds.management.domain;

/**
 * Sealed interface modeling document lifecycle states.
 * Enables exhaustive pattern matching and type-safe state transitions.
 * Each permit class represents a distinct document status with domain semantics.
 */
public sealed interface DocumentStatus permits
    DocumentStatus.Creating,
    DocumentStatus.Active,
    DocumentStatus.Revoked,
    DocumentStatus.Exhausted,
    DocumentStatus.Archived {

    /**
     * Check if the document is available for download.
     */
    boolean isDownloadable();

    /**
     * Get the string representation for persistence (database).
     */
    String persistenceValue();

    /**
     * Create a DocumentStatus from persistence value.
     */
    static DocumentStatus fromPersistenceValue(String value) {
        return switch (value) {
            case "CREATING" -> new Creating();
            case "ACTIVE" -> new Active();
            case "REVOKED" -> new Revoked();
            case "EXHAUSTED" -> new Exhausted();
            case "ARCHIVED" -> new Archived();
            default -> throw new IllegalArgumentException("Unknown status: " + value);
        };
    }

    /**
     * Metadata persisted; object bytes not yet confirmed in storage (or store failed).
     * Not downloadable. Outbox: delete object (idempotent), skip archive, remove DB row.
     */
    record Creating() implements DocumentStatus {
        @Override
        public boolean isDownloadable() {
            return false;
        }

        @Override
        public String persistenceValue() {
            return "CREATING";
        }

        @Override
        public String toString() {
            return "CREATING";
        }
    }

    /**
     * Document is active and available for download.
     */
    record Active() implements DocumentStatus {
        @Override
        public boolean isDownloadable() {
            return true;
        }

        @Override
        public String persistenceValue() {
            return "ACTIVE";
        }

        @Override
        public String toString() {
            return "ACTIVE";
        }
    }

    /**
     * Document has been revoked by an administrator.
     */
    record Revoked() implements DocumentStatus {
        @Override
        public boolean isDownloadable() {
            return false;
        }

        @Override
        public String persistenceValue() {
            return "REVOKED";
        }

        @Override
        public String toString() {
            return "REVOKED";
        }
    }

    /**
     * Document has reached its download limit.
     */
    record Exhausted() implements DocumentStatus {
        @Override
        public boolean isDownloadable() {
            return false;
        }

        @Override
        public String persistenceValue() {
            return "EXHAUSTED";
        }

        @Override
        public String toString() {
            return "EXHAUSTED";
        }
    }

    /**
     * Document has been archived (end of lifecycle).
     */
    record Archived() implements DocumentStatus {
        @Override
        public boolean isDownloadable() {
            return false;
        }

        @Override
        public String persistenceValue() {
            return "ARCHIVED";
        }

        @Override
        public String toString() {
            return "ARCHIVED";
        }
    }
}
