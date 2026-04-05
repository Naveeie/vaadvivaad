// scraper/client/NoOpCaptchaResolver.java
package com.vaadvivaad.scraper.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/*
 * @Profile("dev") — This bean only exists in the dev profile.
 * In prod, a different implementation is loaded.
 *
 * WHY @Profile here instead of @ConditionalOnProperty?
 * Because "dev vs prod" is an environmental concern, not a feature flag.
 * @Profile is cleaner for environment-based bean switching.
 * @ConditionalOnProperty is better when you want runtime feature toggles
 * ("enable this feature without changing the profile").
 */
@Component
@Profile("dev")
public class NoOpCaptchaResolver implements CaptchaResolver {

    private static final Logger log = LoggerFactory.getLogger(NoOpCaptchaResolver.class);

    /*
     * In dev mode, we're testing with saved HTML files — no actual
     * CAPTCHA is encountered. If one IS encountered, we log a clear
     * message rather than silently failing.
     */
    @Override
    public String solve(byte[] captchaImageBytes) {
        log.warn("NoOpCaptchaResolver called — CAPTCHA solving not available in dev profile. " +
                 "Use saved HTML test fixtures instead.");
        return "DEV_SKIP";
    }
}