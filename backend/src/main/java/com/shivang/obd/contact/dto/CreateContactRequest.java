package com.shivang.obd.contact.dto;

import com.shivang.obd.contact.ContactValidation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Contact creation inside an existing group. Ownership is derived from
 * the group; attributes is the documented extension point for future
 * template variables.
 */
public record CreateContactRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,

    @Pattern(regexp = ContactValidation.E164_REGEX, message = "Must be a valid E.164 number, e.g. +918012345678.")
    String phoneNumber,

    @Email @Size(max = 255) String email,

    JsonNode attributes
) {
}
