// scraper/client/ECourtWebClient.java
package com.vaadvivaad.scraper.client;

import com.vaadvivaad.scraper.exception.ScraperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

@Component
public class ECourtWebClient {

    private static final Logger log = LoggerFactory.getLogger(ECourtWebClient.class);

    /*
     * eCourts CNR search endpoint — this is the form POST target.
     * Found by inspecting the Network tab in browser DevTools on
     * services.ecourts.gov.in with a real CNR search.
     */
    private static final String CNR_SEARCH_PATH =
            "/ecourtindia_v6/?p=casestatus/searchByCNRNumber";

    private final WebClient webClient;
    private final CaptchaResolver captchaResolver;

    public ECourtWebClient(
            @Qualifier("eCourtWebClient") WebClient webClient,
            CaptchaResolver captchaResolver) {
        this.webClient = webClient;
        this.captchaResolver = captchaResolver;
    }

    /*
     * FLOW:
     * 1. GET the search page → extract CAPTCHA image URL + session tokens
     * 2. Download the CAPTCHA image bytes
     * 3. Solve CAPTCHA
     * 4. POST form with CNR + CAPTCHA answer
     * 5. Return the HTML of the result page
     *
     * WHY two steps (GET then POST)?
     * The CAPTCHA image URL is dynamically generated per session.
     * You can't POST without first GETting the page to get that session's
     * CAPTCHA. This is intentional by eCourts to prevent direct POSTs.
     */
    public String fetchCaseHtml(String cnrNumber) {
        log.info("Fetching eCourts case page for CNR: {}", cnrNumber);

        try {
            // Step 1: GET the search page
            String searchPageHtml = fetchSearchPage();

            // Step 2: Extract CAPTCHA image URL from the page
            // (In full implementation, Jsoup parses this from the form HTML)
            // For now we note where this fits
            byte[] captchaImageBytes = fetchCaptchaImage(searchPageHtml);

            // Step 3: Solve CAPTCHA
            String captchaSolution = captchaResolver.solve(captchaImageBytes);
            log.debug("CAPTCHA solution obtained for CNR: {}", cnrNumber);

            // Step 4: POST the form with CNR + CAPTCHA solution
            return postCnrSearchForm(cnrNumber, captchaSolution);

        } catch (WebClientResponseException e) {
            log.error("HTTP error fetching eCourts page for CNR {}: {} — {}",
                    cnrNumber, e.getStatusCode(), e.getMessage());
            throw new ScraperException(
                    "eCourts returned HTTP error: " + e.getStatusCode(), e);

        } catch (Exception e) {
            if (e instanceof ScraperException) throw (ScraperException) e;
            log.error("Unexpected error fetching CNR {}: {}", cnrNumber, e.getMessage(), e);
            throw new ScraperException("Failed to fetch case from eCourts: " + e.getMessage(), e);
        }
    }

    private String fetchSearchPage() {
        return webClient.get()
                .uri("/ecourtindia_v6/?p=casestatus/index&app_token=")
                .retrieve()
                /*
                 * WHY .retrieve() not .exchange()?
                 *
                 * .exchange() gives you full control over the response, but
                 * YOU must handle the response body — if you forget to consume
                 * it, you leak connections in the Netty pool.
                 *
                 * .retrieve() auto-handles this. Use .exchange() only when
                 * you need to inspect headers before deciding how to read the body.
                 *
                 * This is a COMMON senior interview question about WebClient.
                 */
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    private byte[] fetchCaptchaImage(String searchPageHtml) {
        /*
         * Parse CAPTCHA image URL from the search page HTML.
         * Typical pattern:
         * <img src="/ecourtindia_v6/vendor/securimage/securimage_show.php?sid=XYZ"
         *      id="captchaimg" />
         *
         * We use Jsoup here specifically to extract this URL.
         */
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(searchPageHtml);
        org.jsoup.nodes.Element captchaImg = doc.getElementById("captchaimg");

        if (captchaImg == null) {
            log.warn("CAPTCHA image not found on search page. " +
                     "eCourts may have changed their HTML structure.");
            return new byte[0]; // NoOpCaptchaResolver handles empty bytes
        }

        String captchaUrl = captchaImg.attr("src");
        log.debug("CAPTCHA image URL: {}", captchaUrl);

        return webClient.get()
                .uri(captchaUrl)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    private String postCnrSearchForm(String cnrNumber, String captchaSolution) {
        /*
         * eCourts uses a standard HTML form POST (application/x-www-form-urlencoded).
         * This is NOT a JSON API. The form fields are:
         * - cino: the CNR number
         * - captcha_code: the CAPTCHA solution
         * - csrf_token: extracted from the search page (important!)
         *
         * NOTE: We're omitting the CSRF token here for simplicity in the
         * initial implementation. The full production version should extract
         * and include it. eCourts may or may not validate it strictly.
         */
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("cino", cnrNumber);
        formData.add("captcha_code", captchaSolution);

        return webClient.post()
                .uri(CNR_SEARCH_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
    }
}