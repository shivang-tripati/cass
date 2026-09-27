package com.shivang.obd.security.token;

import com.shivang.obd.security.AuthenticatedUser;

public interface TokenService {

    IssuedToken issueAccessToken(AuthenticatedUser user);

    record IssuedToken(String tokenValue, long expiresInSeconds) {
    }
}
