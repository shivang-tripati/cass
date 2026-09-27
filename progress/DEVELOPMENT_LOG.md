# OBD Platform Development Log

## Current Version

v0.1.0

---

# Completed Modules
Auth & RBAC Foundation Added
What was implemented
rbac/entity/Role.java

role enum with authority mapping and extensible hierarchy support
auth/entity

UserEntity
RefreshTokenEntity
auth/repository

UserRepository
RefreshTokenRepository
auth/dto

LoginRequest
RefreshRequest
LogoutRequest
LoginResponse
AuthMeResponse
auth/security

ObdUserDetails
ObdUserDetailsService
JwtAuthenticationFilter
auth/token

JwtService
auth/service

AuthenticationService
AuthTokenService
RefreshTokenBlacklistService
auth/controller

AuthController
auth/config

SecurityConfig
Flyway migration

V1__create_users_and_refresh_tokens.sql
V2__seed_test_user.sql
Updated application-dev.yml

JWT secret and expiration properties
Updated pom.xml

Added jjwt-api, jjwt-impl, and jjwt-jackson

Notes
JWT access token includes userId, email, and role
Access token lifetime: 900 seconds
Refresh token lifetime: 604800 seconds
Refresh tokens stored as SHA-256 hash
Refresh tokens support rotation and revocation
Logout revokes refresh token
Security chain protects all endpoints except /api/v1/auth/login and /api/v1/auth/refresh
me endpoint returns authenticated user
Test seed user created:
admin@test.com
password: Password123!

# Technical Decisions

## Decision 001

Date:
2026-07-03

Decision:

Use UUIDv7 instead of BIGINT for public identifiers.

Reason:
Time-Ordered (Sequential)
Prevent ID enumeration.

---

## Decision 002

Date:
2026-07-03

Decision:
Refresh token stored in Redis.

Reason:
Allows token revocation.