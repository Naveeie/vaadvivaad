// scraper/exception/CaptchaException.java
package com.vaadvivaad.scraper.exception;

public class CaptchaException extends RuntimeException {
    public CaptchaException(String message) {
        super(message);
    }
    public CaptchaException(String message, Throwable cause) {
        super(message, cause);
    }
}