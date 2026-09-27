package com.shivang.obd.telephony;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * FreeSWITCH integration configuration.
 * <p>
 * Disabled by default. When enabled, provides connection parameters for
 * the FreeSWITCH outbound dialer adapter.
 * <p>
 * Connection secrets (host/port/password/gateway) are only required when
 * {@code telephony.freeswitch.enabled=true}; a disabled deployment carries
 * no FreeSWITCH configuration at all and must still bind cleanly. Fail-fast
 * validation is preserved for the enabled case via {@link AssertTrue}
 * cross-field checks, so a misconfigured enabled deployment refuses to
 * start just as before.
 * <p>
 * This configuration does NOT include SIP trunk credentials.
 * Those belong to a future trunk configuration design.
 */

@ConfigurationProperties(prefix = "telephony.freeswitch")
@Validated
public class FreeSwitchProperties {

    /**
     * Whether the FreeSWITCH integration is enabled.
     * Defaults to false for safety.
     */
    private boolean enabled = false;

    /**
     * FreeSWITCH Event Socket host.
     * Required when enabled.
     */
    private String host = "localhost";

    /**
     * FreeSWITCH Event Socket port.
     * Defaults to 8021 (standard ESL port).
     */
    @Min(1)
    @Max(65535)
    private int port = 8021;

    /**
     * FreeSWITCH Event Socket password.
     * Should be provided via environment variable, never hard-coded.
     * Required when enabled.
     */
    private String password;

    /**
     * SIP gateway/trunk name configured in FreeSWITCH (sofia profile/gateway).
     * Required when enabled. Example: "my_gateway" or "external".
     */
    private String gateway;

    /**
     * SIP profile name in FreeSWITCH (sofia profile).
     * Defaults to "external".
     */
    @NotBlank
    private String profile = "external";

    /**
     * Connection timeout in seconds.
     */
    @Min(1)
    @Max(300)
    private int connectTimeoutSeconds = 10;

    /**
     * Command timeout in seconds.
     */
    @Min(1)
    @Max(300)
    private int commandTimeoutSeconds = 30;

    /**
     * Cross-field validation: when the integration is enabled, the ESL
     * connection parameters are mandatory. This keeps fail-fast startup
     * semantics for enabled deployments while letting disabled ones (tests,
     * feature-off environments) bind without any FreeSWITCH configuration.
     */
    @AssertTrue(message = "telephony.freeswitch.host must not be blank when the integration is enabled")
    boolean isHostPresentWhenEnabled() {
        return !enabled || (host != null && !host.isBlank());
    }

    @AssertTrue(message = "telephony.freeswitch.password must not be blank when the integration is enabled")
    boolean isPasswordPresentWhenEnabled() {
        return !enabled || (password != null && !password.isBlank());
    }

    @AssertTrue(message = "telephony.freeswitch.gateway must not be blank when the integration is enabled")
    boolean isGatewayPresentWhenEnabled() {
        return !enabled || (gateway != null && !gateway.isBlank());
    }

    // Getters and setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }
}
