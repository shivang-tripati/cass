package com.shivang.obd.security;

import java.util.Optional;

public interface CurrentUserProvider {

    Optional<AuthenticatedUser> current();
}
