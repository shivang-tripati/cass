package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-5G regression tests for the {@link FreeSwitchProperties} binding
 * contract (T-SEC root-cause fix).
 * <p>
 * The integration is disabled by default: a disabled deployment (and every
 * test slice that boots the application class) binds no FreeSWITCH
 * configuration at all, so validation must only demand the ESL connection
 * parameters when {@code telephony.freeswitch.enabled=true}. Fail-fast
 * startup semantics are preserved for enabled deployments.
 */
class FreeSwitchPropertiesValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private static Set<ConstraintViolation<FreeSwitchProperties>> validate(FreeSwitchProperties props) {
        return validator.validate(props);
    }

    private static FreeSwitchProperties disabledDefaults() {
        return new FreeSwitchProperties();
    }

    private static FreeSwitchProperties enabledWithFullConfig() {
        FreeSwitchProperties props = new FreeSwitchProperties();
        props.setEnabled(true);
        props.setHost("freeswitch.internal");
        props.setPort(8021);
        props.setPassword("ClueCon");
        props.setGateway("my_gateway");
        props.setProfile("external");
        return props;
    }

    @Test
    @DisplayName("disabled integration binds cleanly with no connection parameters configured")
    void disabledIntegrationBindsCleanlyWithoutConnectionParameters() {
        Set<ConstraintViolation<FreeSwitchProperties>> violations = validate(disabledDefaults());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("disabled integration binds cleanly even with blank connection parameters")
    void disabledIntegrationBindsCleanlyEvenWithBlankParameters() {
        FreeSwitchProperties props = disabledDefaults();
        props.setHost("");
        props.setPassword("");
        props.setGateway("");

        assertThat(validate(props)).isEmpty();
    }

    @Test
    @DisplayName("enabled integration without password fails validation (fail-fast startup)")
    void enabledIntegrationWithoutPasswordIsRejected() {
        FreeSwitchProperties props = enabledWithFullConfig();
        props.setPassword(null);

        Set<ConstraintViolation<FreeSwitchProperties>> violations = validate(props);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().contains("telephony.freeswitch.password"));
    }

    @Test
    @DisplayName("enabled integration without gateway fails validation (fail-fast startup)")
    void enabledIntegrationWithoutGatewayIsRejected() {
        FreeSwitchProperties props = enabledWithFullConfig();
        props.setGateway("");

        Set<ConstraintViolation<FreeSwitchProperties>> violations = validate(props);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().contains("telephony.freeswitch.gateway"));
    }

    @Test
    @DisplayName("enabled integration with blank host fails validation (fail-fast startup)")
    void enabledIntegrationWithBlankHostIsRejected() {
        FreeSwitchProperties props = enabledWithFullConfig();
        props.setHost(" ");

        Set<ConstraintViolation<FreeSwitchProperties>> violations = validate(props);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().contains("telephony.freeswitch.host"));
    }

    @Test
    @DisplayName("enabled integration with complete configuration passes validation")
    void enabledIntegrationWithCompleteConfigurationIsValid() {
        assertThat(validate(enabledWithFullConfig())).isEmpty();
    }

    @Test
    @DisplayName("port outside 1..65535 is rejected regardless of enabled state")
    void outOfRangePortIsRejected() {
        FreeSwitchProperties props = disabledDefaults();
        props.setPort(0);

        Set<ConstraintViolation<FreeSwitchProperties>> violations = validate(props);

        assertThat(violations)
                .anyMatch(v -> "port".equals(v.getPropertyPath().toString()));
    }

    @Test
    @DisplayName("command timeout outside 1..300 is rejected regardless of enabled state")
    void outOfRangeCommandTimeoutIsRejected() {
        FreeSwitchProperties props = disabledDefaults();
        props.setCommandTimeoutSeconds(301);

        Set<ConstraintViolation<FreeSwitchProperties>> violations = validate(props);

        assertThat(violations)
                .anyMatch(v -> "commandTimeoutSeconds".equals(v.getPropertyPath().toString()));
    }
}
