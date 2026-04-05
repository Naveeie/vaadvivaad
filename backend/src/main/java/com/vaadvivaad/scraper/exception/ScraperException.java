// scraper/exception/ScraperException.java
package com.vaadvivaad.scraper.exception;

public class ScraperException extends RuntimeException {
    public ScraperException(String message) {
        super(message);
    }
    public ScraperException(String message, Throwable cause) {
        super(message, cause);
    }
}