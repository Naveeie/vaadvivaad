// scraper/client/CaptchaResolver.java
package com.vaadvivaad.scraper.client;

/*
 * STRATEGY PATTERN — Why this matters architecturally:
 *
 * Without this interface, your ScraperService has an if/else block:
 *   if (env == DEV) { ... } else if (env == PROD && service == TWOCAPTCHA) { ... }
 *
 * That's a violation of Open/Closed Principle — every new CAPTCHA strategy
 * requires modifying ScraperService.
 *
 * With this interface: ScraperService just calls resolver.solve(imageBytes).
 * New strategies are added by creating new implementations, zero changes
 * to ScraperService. This is exactly what senior engineers mean by
 * "closed for modification, open for extension."
 *
 * INTERVIEW: "How do you handle external dependencies that might change?"
 * Answer: "I wrap them behind interfaces so my business logic doesn't
 * change when the external tool changes."
 */
public interface CaptchaResolver {

    /**
     * Given a CAPTCHA image as bytes, return the solved text.
     *
     * @param captchaImageBytes raw bytes of the CAPTCHA image (PNG/JPEG)
     * @return the text visible in the CAPTCHA
     * @throws CaptchaException if solving fails or times out
     */
    String solve(byte[] captchaImageBytes);
}