package com.shivang.obd.campaign.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * The single authority for validating a campaign webhook endpoint (VB-7C.2).
 *
 * <h2>Why a dedicated authority</h2>
 *
 * <p>VB-7C.2 deliberately introduces <b>one</b> URL policy in the platform
 * rather than a rule inline in a service. The repository had no URL validation
 * utility at the time of this phase, so this class is that one; a second
 * implementation elsewhere would be a divergence waiting to happen.
 *
 * <h2>What is validated, and what is not</h2>
 *
 * <p>Endpoint validation here is <b>configuration</b> validation: is this a
 * well-formed, absolute, externally-reachable HTTP(S) URL that we could
 * eventually post to? It is deliberately <b>not</b> an SSRF defence. Reaching
 * private address space, DNS-rebinding and redirect-following are all
 * delivery-time concerns, and they become real only when a delivery transport
 * exists — which VB-7C.2 explicitly does not build.
 *
 * <p>Introducing a half-built SSRF filter now would be worse than none: it would
 * create a second, weaker security boundary that a future delivery subsystem
 * would have to discover and replace. When delivery is implemented, this class
 * is the natural place to extend, and the extension will then be covered by the
 * tests that matter.
 *
 * <h2>Accepted schemes</h2>
 *
 * <p>Only {@code https} and {@code http}. Everything else is rejected —
 * including {@code file}, {@code ftp}, {@code javascript}, {@code data} and
 * {@code jar}, none of which is a web-hook target and several of which would be
 * actively dangerous to fetch. {@code http} is accepted because on-premise
 * deployments legitimately terminate integrations on an internal listener over
 * plain HTTP; the platform does not require TLS.
 */
public final class WebhookEndpointValidator {

    /** Maximum endpoint length, bounding what can be persisted. */
    public static final int MAX_ENDPOINT_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");

    private WebhookEndpointValidator() {
    }

    /**
     * Validates an endpoint, returning the normalised form to persist.
     *
     * @param endpoint the configured endpoint, possibly null or blank
     * @return the endpoint unchanged when it is valid
     * @throws CampaignConfigInvalidException when it is not a usable web-hook
     *         target
     */
    public static String requireValid(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint is required when the webhook is "
                            + "enabled and must be an absolute http(s) URL.");
        }
        String trimmed = endpoint.trim();
        if (trimmed.length() > MAX_ENDPOINT_LENGTH) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint must be at most "
                            + MAX_ENDPOINT_LENGTH + " characters.");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint is not a valid URL: " + e.getReason());
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint must be an absolute URL, including a "
                            + "scheme (for example https://example.com/hooks/campaign).");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint scheme '" + scheme + "' is not "
                            + "supported; use http or https.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint must include a host.");
        }
        // A userinfo component embeds credentials in the URL itself, which is
        // exactly the secret storage this configuration must not enable.
        if (uri.getRawUserInfo() != null) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.endpoint must not embed credentials; web-hook "
                            + "signing is a separate, unimplemented concern and credentials do "
                            + "not belong in a URL.");
        }
        return trimmed;
    }

    /** Whether an endpoint is a usable web-hook target. */
    public static boolean isValid(String endpoint) {
        try {
            requireValid(endpoint);
            return true;
        } catch (CampaignConfigInvalidException e) {
            return false;
        }
    }
}
