package za.co.capitec.sds.management.exception;

public final class DocumentUploadException {

    private DocumentUploadException() {
    }

    public static class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(String msg) {
            super(msg);
        }
    }

    public static class InvalidFileTypeException extends RuntimeException {
        public InvalidFileTypeException(String msg) {
            super(msg);
        }
    }
}
